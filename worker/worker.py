"""
InfraQ Worker — Parallel LLM Inference Worker

Implements 4 scheduling strategies inspired by vLLM and SGLang:
  1. sequential  — baseline: process one request at a time
  2. static      — collect N requests into a batch, process all, repeat
  3. continuous  — maintain K concurrent slots, refill immediately (vLLM-inspired)
  4. cached      — continuous + prompt cache priority (SGLang-inspired)

The worker consumes from RabbitMQ, calls Ollama for inference,
and publishes results to Redis (fast path) + PostgreSQL (durable).
"""

import asyncio
import hashlib
import json
import logging
import os
import signal
import time

import aio_pika
import aiohttp
import psycopg2
import psycopg2.extras
import redis

# ---------------------------------------------------------------------------
# Configuration (all via environment variables)
# ---------------------------------------------------------------------------
RABBITMQ_HOST   = os.getenv("RABBITMQ_HOST", "localhost")
REDIS_HOST      = os.getenv("REDIS_HOST", "localhost")
OLLAMA_HOST     = os.getenv("OLLAMA_HOST", "http://localhost:11434")
MODEL           = os.getenv("MODEL", "qwen2.5:1.5b")
NUM_SLOTS       = int(os.getenv("NUM_CONCURRENT_SLOTS", "4"))
STRATEGY        = os.getenv("SCHEDULING_STRATEGY", "continuous")
WORKER_ID       = os.getenv("HOSTNAME", "worker-1")
QUEUE_NAME      = "inference.requests"
CACHE_TTL       = 3600  # 1 hour

# PostgreSQL
PG_HOST     = os.getenv("POSTGRES_HOST", "localhost")
PG_DB       = os.getenv("POSTGRES_DB", "infraq")
PG_USER     = os.getenv("POSTGRES_USER", "postgres")
PG_PASSWORD = os.getenv("POSTGRES_PASSWORD", "postgres")

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("infraq-worker")

# ---------------------------------------------------------------------------
# Worker
# ---------------------------------------------------------------------------
class InferenceWorker:
    def __init__(self):
        self.rdb = redis.Redis(host=REDIS_HOST, port=6379, decode_responses=True)
        self.pg = None
        self.session = None
        self.running = True
        self._active_tasks = 0

    # --- Lifecycle -----------------------------------------------------------

    async def start(self):
        log.info("Worker starting: strategy=%s, slots=%d, model=%s", STRATEGY, NUM_SLOTS, MODEL)

        self.session = aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=300)  # 5 min max per request
        )
        self.pg = psycopg2.connect(
            host=PG_HOST, dbname=PG_DB, user=PG_USER, password=PG_PASSWORD
        )
        self.pg.autocommit = True

        # Wait for Ollama to be ready
        await self._wait_for_ollama()

        # Connect to RabbitMQ
        connection = await aio_pika.connect_robust(
            f"amqp://guest:guest@{RABBITMQ_HOST}/",
            connection_name="infraq-worker"
        )
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=NUM_SLOTS * 2)
        queue = await channel.declare_queue(QUEUE_NAME, durable=True)

        # Check for runtime strategy override from Redis
        strategy = self.rdb.get("config:strategy") or STRATEGY
        num_slots = int(self.rdb.get("config:num_slots") or NUM_SLOTS)

        log.info("Starting strategy: %s (slots=%d)", strategy, num_slots)

        if strategy == "sequential":
            await self.run_sequential(queue)
        elif strategy == "static":
            await self.run_static_batch(queue, num_slots)
        elif strategy == "cached":
            await self.run_cache_aware(queue, num_slots)
        else:  # default: continuous
            await self.run_continuous(queue, num_slots)

    async def _wait_for_ollama(self):
        """Wait until Ollama is reachable AND the required model is loaded."""
        for attempt in range(120):
            try:
                async with self.session.get(f"{OLLAMA_HOST}/api/tags") as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        models = [m["name"] for m in data.get("models", [])]
                        # Check if our model is available (match with or without :latest tag)
                        model_base = MODEL.split(":")[0]
                        if any(model_base in m for m in models):
                            log.info("Ollama ready. Model '%s' found. All models: %s", MODEL, models)
                            return
                        else:
                            log.info("Ollama up but model '%s' not found. Available: %s (attempt %d)",
                                     MODEL, models, attempt + 1)
            except Exception:
                log.info("Waiting for Ollama... (attempt %d)", attempt + 1)
            await asyncio.sleep(3)
        raise RuntimeError(f"Model '{MODEL}' not available after 360 seconds")

    # -----------------------------------------------------------------------
    # STRATEGY 1: SEQUENTIAL (baseline)
    # Process one request at a time. No concurrency. Worst performance.
    # -----------------------------------------------------------------------
    async def run_sequential(self, queue):
        log.info("[SEQUENTIAL] Processing one request at a time")
        async with queue.iterator() as q:
            async for message in q:
                if not self.running:
                    break
                await self._process_message(message)

    # -----------------------------------------------------------------------
    # STRATEGY 2: STATIC BATCHING
    # Collect batch_size requests (or timeout), process all concurrently,
    # wait for ALL to complete, then collect next batch.
    # Key weakness: fastest request waits for slowest in the batch.
    # -----------------------------------------------------------------------
    async def run_static_batch(self, queue, batch_size):
        log.info("[STATIC BATCH] Batch size=%d", batch_size)
        batch_timeout = 3.0  # seconds

        # Use a single iterator (= single consumer) to avoid losing messages
        async with queue.iterator() as q:
            while self.running:
                batch = []

                # Collect up to batch_size messages or until timeout
                try:
                    async with asyncio.timeout(batch_timeout):
                        async for message in q:
                            batch.append(message)
                            if len(batch) >= batch_size:
                                break
                except asyncio.TimeoutError:
                    pass

                if not batch:
                    continue

                log.info("[STATIC BATCH] Processing batch of %d", len(batch))

                # Process entire batch concurrently, wait for ALL
                tasks = [self._process_message(msg) for msg in batch]
                await asyncio.gather(*tasks)

                # Only after all complete do we collect the next batch
                log.info("[STATIC BATCH] Batch complete")

    # -----------------------------------------------------------------------
    # STRATEGY 3: CONTINUOUS DISPATCHING (inspired by vLLM)
    # Maintain num_slots concurrent processing slots.
    # When ANY slot frees, immediately dispatch the next request.
    # No idle waiting. Ollama internally batches concurrent requests.
    # -----------------------------------------------------------------------
    async def run_continuous(self, queue, num_slots):
        log.info("[CONTINUOUS] Maintaining %d concurrent slots", num_slots)
        semaphore = asyncio.Semaphore(num_slots)

        async def process_with_slot(message):
            async with semaphore:
                await self._process_message(message)

        async with queue.iterator() as q:
            async for message in q:
                if not self.running:
                    break
                # Fire-and-forget: semaphore limits concurrency
                asyncio.create_task(process_with_slot(message))

    # -----------------------------------------------------------------------
    # STRATEGY 4: CACHE-AWARE DISPATCHING (inspired by SGLang LPM)
    # Like continuous, but checks prompt cache before dispatching.
    # Cache hits return instantly without using an Ollama slot.
    # Cache misses go through the normal continuous path.
    # This approximates SGLang's Longest Prefix Match scheduling.
    # -----------------------------------------------------------------------
    async def run_cache_aware(self, queue, num_slots):
        log.info("[CACHE-AWARE] %d slots + prompt cache", num_slots)
        semaphore = asyncio.Semaphore(num_slots)

        async def process_with_cache(message):
            data = json.loads(message.body)
            req_id = data["id"]
            prompt = data["prompt"]
            submitted_at = data["submitted_at"]

            # Check cache BEFORE acquiring a slot
            cache_key = self._prompt_hash(prompt)
            cached = self.rdb.get(f"cache:{cache_key}")

            if cached:
                # Cache hit — instant return, no Ollama slot used
                queue_wait = int(time.time() * 1000) - submitted_at
                log.info("[CACHE HIT] req=%s (%.0fms queue wait)", req_id[:8], queue_wait)
                self._publish_result(req_id, cached, queue_wait, 0, cache_hit=True)
                await message.ack()
                return

            # Cache miss — need a slot to call Ollama
            async with semaphore:
                await self._process_message_inner(data, message)

        async with queue.iterator() as q:
            async for message in q:
                if not self.running:
                    break
                asyncio.create_task(process_with_cache(message))

    # -----------------------------------------------------------------------
    # Core processing logic (shared by all strategies)
    # -----------------------------------------------------------------------
    async def _process_message(self, message):
        """Parse message and process. Used by sequential/static/continuous."""
        try:
            data = json.loads(message.body)
            await self._process_message_inner(data, message)
        except Exception as e:
            log.error("Failed to process message: %s", e)
            await message.nack(requeue=True)

    async def _process_message_inner(self, data, message):
        """Core processing: cache check → Ollama call → publish result."""
        req_id = data["id"]
        prompt = data["prompt"]
        submitted_at = data["submitted_at"]

        try:
            # Update status to PROCESSING
            self.rdb.set(f"req:{req_id}:status", "PROCESSING")
            queue_wait = int(time.time() * 1000) - submitted_at

            # Check prompt cache (for non-cache-aware strategies too)
            cache_key = self._prompt_hash(prompt)
            cached = self.rdb.get(f"cache:{cache_key}")
            if cached:
                log.info("[CACHE HIT] req=%s", req_id[:8])
                self._publish_result(req_id, cached, queue_wait, 0, cache_hit=True)
                await message.ack()
                return

            # Call Ollama
            start_time = time.time()
            result, tokens_in, tokens_out = await self._call_ollama(prompt)
            inference_ms = int((time.time() - start_time) * 1000)

            # Cache the result
            self.rdb.setex(f"cache:{cache_key}", CACHE_TTL, result)

            # Publish result
            total_ms = int(time.time() * 1000) - submitted_at
            self._publish_result(
                req_id, result, queue_wait, inference_ms,
                cache_hit=False, total_ms=total_ms,
                tokens_in=tokens_in, tokens_out=tokens_out
            )

            log.info("Completed req=%s inference=%dms queue_wait=%dms total=%dms",
                     req_id[:8], inference_ms, queue_wait, total_ms)
            await message.ack()

        except Exception as e:
            log.error("Error processing req=%s: %s", req_id[:8], e)
            self.rdb.set(f"req:{req_id}:status", "FAILED")
            self.rdb.set(f"req:{req_id}:error", str(e))
            self.rdb.incr("metrics:total_failed")
            self._update_pg_error(req_id, str(e))
            await message.ack()  # don't requeue — mark as failed

    async def _call_ollama(self, prompt):
        """Call Ollama's generate API. Returns (response_text, tokens_in, tokens_out)."""
        payload = {
            "model": MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {
                "temperature": 0.7,
                "num_predict": 256,
            }
        }

        async with self.session.post(
            f"{OLLAMA_HOST}/api/generate", json=payload
        ) as resp:
            if resp.status != 200:
                body = await resp.text()
                raise RuntimeError(f"Ollama returned {resp.status}: {body}")

            body = await resp.json()
            response = body.get("response", "")
            tokens_in = body.get("prompt_eval_count", 0)
            tokens_out = body.get("eval_count", 0)
            return response, tokens_in, tokens_out

    # -----------------------------------------------------------------------
    # Result publishing
    # -----------------------------------------------------------------------
    def _publish_result(self, req_id, output, queue_wait_ms, inference_ms,
                        cache_hit=False, total_ms=None, tokens_in=0, tokens_out=0):
        """Write result to Redis (fast path) and PostgreSQL (durable)."""
        if total_ms is None:
            total_ms = queue_wait_ms + inference_ms

        # Redis — fast path for client polling
        pipe = self.rdb.pipeline()
        pipe.set(f"req:{req_id}:status", "COMPLETED")
        pipe.set(f"req:{req_id}:result", output)
        pipe.set(f"req:{req_id}:latency_ms", str(total_ms))
        pipe.set(f"req:{req_id}:queue_wait_ms", str(queue_wait_ms))
        pipe.set(f"req:{req_id}:inference_ms", str(inference_ms))
        pipe.set(f"req:{req_id}:cache_hit", str(cache_hit).lower())
        pipe.set(f"req:{req_id}:model", MODEL)
        pipe.set(f"req:{req_id}:completed_at", str(int(time.time() * 1000)))
        pipe.incr("metrics:total_completed")
        if cache_hit:
            pipe.incr("metrics:total_cache_hits")
        # Set TTL on all request keys (1 hour)
        for suffix in [":status", ":result", ":latency_ms", ":queue_wait_ms",
                       ":inference_ms", ":cache_hit", ":model", ":completed_at", ":submitted"]:
            pipe.expire(f"req:{req_id}{suffix}", 3600)
        pipe.execute()

        # PostgreSQL — durable storage
        try:
            with self.pg.cursor() as cur:
                cur.execute(
                    "UPDATE infraq.inference_requests SET "
                    "status='COMPLETED', worker_id=%s, started_at=%s, completed_at=%s, "
                    "queue_wait_ms=%s, inference_ms=%s, total_latency_ms=%s, cache_hit=%s "
                    "WHERE id=%s::uuid",
                    (WORKER_ID,
                     int(time.time() * 1000) - inference_ms,
                     int(time.time() * 1000),
                     queue_wait_ms, inference_ms, total_ms, cache_hit, req_id)
                )
                cur.execute(
                    "INSERT INTO infraq.inference_results "
                    "(request_id, output, model, tokens_in, tokens_out, created_at) "
                    "VALUES (%s::uuid, %s, %s, %s, %s, %s) "
                    "ON CONFLICT (request_id) DO UPDATE SET output=EXCLUDED.output",
                    (req_id, output, MODEL, tokens_in, tokens_out, int(time.time() * 1000))
                )
        except Exception as e:
            log.error("PostgreSQL write failed for req=%s: %s", req_id[:8], e)

    def _update_pg_error(self, req_id, error):
        try:
            with self.pg.cursor() as cur:
                cur.execute(
                    "UPDATE infraq.inference_requests SET status='FAILED', "
                    "error_message=%s, completed_at=%s WHERE id=%s::uuid",
                    (error, int(time.time() * 1000), req_id)
                )
        except Exception as e:
            log.error("PostgreSQL error update failed: %s", e)

    def _prompt_hash(self, prompt):
        """SHA-256 hash of model+prompt for caching."""
        return hashlib.sha256(f"{MODEL}:{prompt}".encode()).hexdigest()[:16]


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------
async def main():
    worker = InferenceWorker()

    # Graceful shutdown
    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, lambda: setattr(worker, 'running', False))

    await worker.start()

if __name__ == "__main__":
    asyncio.run(main())
