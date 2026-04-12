"""
InfraQ Worker - Parallel LLM Inference Worker

Implements 4 scheduling strategies inspired by vLLM and SGLang:
  1. sequential  - process one request at a time
  2. static      - collect N requests into a batch, process all, repeat
  3. continuous  - maintain K concurrent slots, refill immediately
  4. cached      - continuous + prompt cache priority

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
import redis

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
RABBITMQ_HOST = os.getenv("RABBITMQ_HOST", "localhost")
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
MODEL = os.getenv("MODEL", "qwen2.5:1.5b")
NUM_SLOTS = int(os.getenv("NUM_CONCURRENT_SLOTS", "4"))
STRATEGY = os.getenv("SCHEDULING_STRATEGY", "cached")
WORKER_ID = os.getenv("HOSTNAME", "worker-1")
QUEUE_NAME = "inference.requests"
CACHE_TTL = 3600
ALLOWED_STRATEGIES = {"sequential", "static", "continuous", "cached"}

# PostgreSQL
PG_HOST = os.getenv("POSTGRES_HOST", "localhost")
PG_DB = os.getenv("POSTGRES_DB", "infraq")
PG_USER = os.getenv("POSTGRES_USER", "postgres")
PG_PASSWORD = os.getenv("POSTGRES_PASSWORD", "postgres")

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("infraq-worker")


class InferenceWorker:
    def __init__(self):
        self.rdb = redis.Redis(host=REDIS_HOST, port=6379, decode_responses=True)
        self.pg = None
        self.session = None
        self.connection = None
        self.channel = None
        self.queue = None
        self.dispatch_queue = asyncio.Queue()
        self.running = True

        self.runtime_strategy = STRATEGY
        self.runtime_slots = max(1, NUM_SLOTS)
        self.runtime_phase = "STARTING"
        self._active_requests = 0

        self._background_tasks = set()
        self._heartbeat_task = None
        self._consumer_tag = None

    async def start(self):
        log.info("Worker booting: default_strategy=%s slots=%d model=%s", STRATEGY, NUM_SLOTS, MODEL)

        self.session = aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=300)
        )
        self.pg = psycopg2.connect(
            host=PG_HOST, dbname=PG_DB, user=PG_USER, password=PG_PASSWORD
        )
        self.pg.autocommit = True

        await self._wait_for_ollama()

        self.connection = await aio_pika.connect_robust(
            f"amqp://guest:guest@{RABBITMQ_HOST}/",
            connection_name="infraq-worker"
        )
        self.channel = await self.connection.channel()
        self.queue = await self.channel.declare_queue(QUEUE_NAME, durable=True)
        self._consumer_tag = await self.queue.consume(self._enqueue_message, no_ack=False)

        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

        try:
            while self.running:
                strategy, num_slots = self._read_desired_config()
                await self.channel.set_qos(prefetch_count=max(num_slots * 2, 1))
                self._set_runtime(strategy=strategy, slots=num_slots, phase="READY")
                log.info("Worker runtime ready: strategy=%s slots=%d", strategy, num_slots)

                if strategy == "sequential":
                    await self.run_sequential(strategy, num_slots)
                elif strategy == "static":
                    await self.run_static_batch(strategy, num_slots)
                elif strategy == "cached":
                    await self.run_cache_aware(strategy, num_slots)
                else:
                    await self.run_continuous(strategy, num_slots)
        finally:
            await self._shutdown()

    async def run_sequential(self, expected_strategy, expected_slots):
        log.info("[SEQUENTIAL] Processing one request at a time")
        self._set_runtime(phase="RUNNING")

        while self.running:
            if self._desired_config_changed(expected_strategy, expected_slots):
                self._set_runtime(phase="SWITCHING")
                return

            message = await self._next_message()
            if message is None:
                continue

            if self._desired_config_changed(expected_strategy, expected_slots):
                await self._safe_nack(message, requeue=True)
                self._set_runtime(phase="SWITCHING")
                return

            await self._process_message(message)

    async def run_static_batch(self, expected_strategy, batch_size):
        log.info("[STATIC BATCH] Batch size=%d", batch_size)
        self._set_runtime(phase="RUNNING")
        batch_timeout = 3.0

        while self.running:
            if self._desired_config_changed(expected_strategy, batch_size):
                self._set_runtime(phase="SWITCHING")
                return

            batch = []
            deadline = time.monotonic() + batch_timeout

            while self.running and len(batch) < batch_size:
                remaining = deadline - time.monotonic()
                if remaining <= 0 and batch:
                    break

                if self._desired_config_changed(expected_strategy, batch_size) and not batch:
                    self._set_runtime(phase="SWITCHING")
                    return

                timeout = 0.25 if remaining <= 0 else min(remaining, 0.5)
                message = await self._next_message(timeout=timeout)
                if message is None:
                    if time.monotonic() >= deadline:
                        break
                    continue

                if self._desired_config_changed(expected_strategy, batch_size) and not batch:
                    await self._safe_nack(message, requeue=True)
                    self._set_runtime(phase="SWITCHING")
                    return

                batch.append(message)

            if not batch:
                continue

            log.info("[STATIC BATCH] Processing batch of %d", len(batch))
            await asyncio.gather(*(self._process_message(message) for message in batch))
            log.info("[STATIC BATCH] Batch complete")

    async def run_continuous(self, expected_strategy, num_slots):
        log.info("[CONTINUOUS] Maintaining %d concurrent slots", num_slots)
        self._set_runtime(phase="RUNNING")
        semaphore = asyncio.Semaphore(num_slots)

        async def process_with_slot(message):
            async with semaphore:
                await self._process_message(message)

        while self.running:
            if self._desired_config_changed(expected_strategy, num_slots):
                self._set_runtime(phase="DRAINING")
                await self._drain_background_tasks()
                self._set_runtime(phase="SWITCHING")
                return

            message = await self._next_message()
            if message is None:
                continue

            if self._desired_config_changed(expected_strategy, num_slots):
                await self._safe_nack(message, requeue=True)
                self._set_runtime(phase="DRAINING")
                await self._drain_background_tasks()
                self._set_runtime(phase="SWITCHING")
                return

            self._track_task(asyncio.create_task(process_with_slot(message)))

    async def run_cache_aware(self, expected_strategy, num_slots):
        log.info("[CACHE-AWARE] %d slots + prompt cache", num_slots)
        self._set_runtime(phase="RUNNING")
        semaphore = asyncio.Semaphore(num_slots)

        async def process_with_cache(message):
            async with semaphore:
                await self._process_message(message, allow_cache=True)

        while self.running:
            if self._desired_config_changed(expected_strategy, num_slots):
                self._set_runtime(phase="DRAINING")
                await self._drain_background_tasks()
                self._set_runtime(phase="SWITCHING")
                return

            message = await self._next_message()
            if message is None:
                continue

            if self._desired_config_changed(expected_strategy, num_slots):
                await self._safe_nack(message, requeue=True)
                self._set_runtime(phase="DRAINING")
                await self._drain_background_tasks()
                self._set_runtime(phase="SWITCHING")
                return

            self._track_task(asyncio.create_task(process_with_cache(message)))

    async def _wait_for_ollama(self):
        for attempt in range(120):
            try:
                async with self.session.get(f"{OLLAMA_HOST}/api/tags") as response:
                    if response.status == 200:
                        data = await response.json()
                        models = [entry["name"] for entry in data.get("models", [])]
                        model_base = MODEL.split(":")[0]
                        if any(model_base in model for model in models):
                            log.info("Ollama ready. Model '%s' found. Available models: %s", MODEL, models)
                            return
                        log.info("Ollama up but model '%s' not found. Available: %s (attempt %d)",
                                 MODEL, models, attempt + 1)
            except Exception:
                log.info("Waiting for Ollama... (attempt %d)", attempt + 1)
            await asyncio.sleep(3)

        raise RuntimeError(f"Model '{MODEL}' not available after 360 seconds")

    async def _process_message(self, message, allow_cache=False):
        self._active_requests += 1
        try:
            data = json.loads(message.body)
            await self._process_message_inner(data, message, allow_cache=allow_cache)
        except Exception as error:
            log.error("Failed to process message: %s", error)
            await self._safe_nack(message, requeue=True)
        finally:
            self._active_requests = max(0, self._active_requests - 1)

    async def _process_message_inner(self, data, message, allow_cache=False):
        req_id = data["id"]
        prompt = data["prompt"]
        submitted_at = data["submitted_at"]

        try:
            self.rdb.set(f"req:{req_id}:status", "PROCESSING")
            queue_wait = int(time.time() * 1000) - submitted_at

            cache_key = self._prompt_hash(prompt)
            if allow_cache:
                cached = self.rdb.get(f"cache:{cache_key}")
                if cached:
                    log.info("[CACHE HIT] req=%s", req_id[:8])
                    self._publish_result(req_id, cached, queue_wait, 0, cache_hit=True)
                    await self._safe_ack(message)
                    return

            start_time = time.time()
            result, tokens_in, tokens_out = await self._call_ollama(prompt)
            inference_ms = int((time.time() - start_time) * 1000)

            if allow_cache:
                self.rdb.setex(f"cache:{cache_key}", CACHE_TTL, result)

            total_ms = int(time.time() * 1000) - submitted_at
            self._publish_result(
                req_id, result, queue_wait, inference_ms,
                cache_hit=False, total_ms=total_ms,
                tokens_in=tokens_in, tokens_out=tokens_out
            )

            log.info("Completed req=%s inference=%dms queue_wait=%dms total=%dms",
                     req_id[:8], inference_ms, queue_wait, total_ms)
            await self._safe_ack(message)

        except Exception as error:
            log.error("Error processing req=%s: %s", req_id[:8], error)
            self.rdb.set(f"req:{req_id}:status", "FAILED")
            self.rdb.set(f"req:{req_id}:error", str(error))
            self.rdb.incr("metrics:total_failed")
            self._update_pg_error(req_id, str(error))
            await self._safe_ack(message)

    async def _call_ollama(self, prompt):
        payload = {
            "model": MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {
                "temperature": 0.7,
                "num_predict": 256,
            }
        }

        async with self.session.post(f"{OLLAMA_HOST}/api/generate", json=payload) as response:
            if response.status != 200:
                body = await response.text()
                raise RuntimeError(f"Ollama returned {response.status}: {body}")

            body = await response.json()
            return (
                body.get("response", ""),
                body.get("prompt_eval_count", 0),
                body.get("eval_count", 0)
            )

    def _publish_result(self, req_id, output, queue_wait_ms, inference_ms,
                        cache_hit=False, total_ms=None, tokens_in=0, tokens_out=0):
        if total_ms is None:
            total_ms = queue_wait_ms + inference_ms

        pipeline = self.rdb.pipeline()
        pipeline.set(f"req:{req_id}:status", "COMPLETED")
        pipeline.set(f"req:{req_id}:result", output)
        pipeline.set(f"req:{req_id}:latency_ms", str(total_ms))
        pipeline.set(f"req:{req_id}:queue_wait_ms", str(queue_wait_ms))
        pipeline.set(f"req:{req_id}:inference_ms", str(inference_ms))
        pipeline.set(f"req:{req_id}:cache_hit", str(cache_hit).lower())
        pipeline.set(f"req:{req_id}:model", MODEL)
        pipeline.set(f"req:{req_id}:completed_at", str(int(time.time() * 1000)))
        pipeline.incr("metrics:total_completed")
        if cache_hit:
            pipeline.incr("metrics:total_cache_hits")
        for suffix in [
            ":status", ":result", ":latency_ms", ":queue_wait_ms",
            ":inference_ms", ":cache_hit", ":model", ":completed_at", ":submitted"
        ]:
            pipeline.expire(f"req:{req_id}{suffix}", 3600)
        pipeline.execute()

        try:
            with self.pg.cursor() as cursor:
                cursor.execute(
                    "UPDATE infraq.inference_requests SET "
                    "status='COMPLETED', worker_id=%s, started_at=%s, completed_at=%s, "
                    "queue_wait_ms=%s, inference_ms=%s, total_latency_ms=%s, cache_hit=%s "
                    "WHERE id=%s::uuid",
                    (
                        WORKER_ID,
                        int(time.time() * 1000) - inference_ms,
                        int(time.time() * 1000),
                        queue_wait_ms,
                        inference_ms,
                        total_ms,
                        cache_hit,
                        req_id
                    )
                )
                cursor.execute(
                    "INSERT INTO infraq.inference_results "
                    "(request_id, output, model, tokens_in, tokens_out, created_at) "
                    "VALUES (%s::uuid, %s, %s, %s, %s, %s) "
                    "ON CONFLICT (request_id) DO UPDATE SET output=EXCLUDED.output",
                    (req_id, output, MODEL, tokens_in, tokens_out, int(time.time() * 1000))
                )
        except Exception as error:
            log.error("PostgreSQL write failed for req=%s: %s", req_id[:8], error)

    def _update_pg_error(self, req_id, error):
        try:
            with self.pg.cursor() as cursor:
                cursor.execute(
                    "UPDATE infraq.inference_requests SET status='FAILED', "
                    "error_message=%s, completed_at=%s WHERE id=%s::uuid",
                    (error, int(time.time() * 1000), req_id)
                )
        except Exception as update_error:
            log.error("PostgreSQL error update failed: %s", update_error)

    def _prompt_hash(self, prompt):
        return hashlib.sha256(f"{MODEL}:{prompt}".encode()).hexdigest()[:16]

    def _read_desired_config(self):
        strategy = (self.rdb.get("config:strategy") or STRATEGY).strip().lower()
        if strategy not in ALLOWED_STRATEGIES:
            strategy = "cached"

        try:
            num_slots = max(1, int(self.rdb.get("config:num_slots") or NUM_SLOTS))
        except ValueError:
            num_slots = max(1, NUM_SLOTS)

        if strategy == "sequential":
            num_slots = 1

        return strategy, num_slots

    def _desired_config_changed(self, current_strategy, current_slots):
        desired_strategy, desired_slots = self._read_desired_config()
        return desired_strategy != current_strategy or desired_slots != current_slots

    def _set_runtime(self, strategy=None, slots=None, phase=None):
        if strategy is not None:
            self.runtime_strategy = strategy
        if slots is not None:
            self.runtime_slots = slots
        if phase is not None:
            self.runtime_phase = phase

    async def _heartbeat_loop(self):
        try:
            while self.running:
                self._write_runtime_state()
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            pass
        finally:
            self.runtime_phase = "STOPPED"
            self._write_runtime_state()

    def _write_runtime_state(self):
        pipeline = self.rdb.pipeline()
        pipeline.set("worker:runtime:strategy", self.runtime_strategy)
        pipeline.set("worker:runtime:num_slots", str(self.runtime_slots))
        pipeline.set("worker:runtime:phase", self.runtime_phase)
        pipeline.set("worker:runtime:active_requests", str(self._active_requests))
        pipeline.set("worker:runtime:buffered_messages", str(self.dispatch_queue.qsize()))
        pipeline.set("worker:runtime:heartbeat_at", str(int(time.time() * 1000)))
        pipeline.execute()

    def _track_task(self, task):
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    async def _drain_background_tasks(self):
        if not self._background_tasks:
            return

        pending = list(self._background_tasks)
        await asyncio.gather(*pending, return_exceptions=True)

    async def _enqueue_message(self, message):
        await self.dispatch_queue.put(message)

    async def _next_message(self, timeout=0.5):
        try:
            return await asyncio.wait_for(self.dispatch_queue.get(), timeout=timeout)
        except asyncio.TimeoutError:
            return None

    async def _safe_ack(self, message):
        try:
            await message.ack()
            return True
        except Exception as error:
            log.error("Failed to ack message: %s", error)
            return False

    async def _safe_nack(self, message, requeue=True):
        try:
            await message.nack(requeue=requeue)
            return True
        except Exception as error:
            log.error("Failed to nack message: %s", error)
            return False

    async def _shutdown(self):
        self._set_runtime(phase="STOPPING")
        await self._drain_background_tasks()

        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass

        if self.session:
            await self.session.close()

        if self.queue and self._consumer_tag:
            try:
                await self.queue.cancel(self._consumer_tag)
            except Exception:
                pass

        if self.connection:
            await self.connection.close()

        if self.pg:
            self.pg.close()


async def main():
    worker = InferenceWorker()
    loop = asyncio.get_event_loop()

    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, lambda: setattr(worker, "running", False))

    await worker.start()


if __name__ == "__main__":
    asyncio.run(main())
