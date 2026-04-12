<div align="center">

# InfraQ

**A scheduling gateway for shared local LLM inference on macOS.**

<p>
  <img alt="platform" src="https://img.shields.io/badge/platform-macOS-black?logo=apple">
  <img alt="java" src="https://img.shields.io/badge/gateway-Java%2017%20%7C%20Spring%20Boot-6DB33F?logo=springboot&logoColor=white">
  <img alt="python" src="https://img.shields.io/badge/worker-Python%203.11-3776AB?logo=python&logoColor=white">
  <img alt="ollama" src="https://img.shields.io/badge/runtime-Ollama%20on%20host-111827">
  <img alt="docker" src="https://img.shields.io/badge/orchestration-Docker%20Compose-2496ED?logo=docker&logoColor=white">
</p>

</div>

InfraQ is a lightweight inference gateway designed for **shared local LLM serving on Apple hardware**. It keeps Ollama on the **host machine** so macOS can use **Metal acceleration**, while the gateway, worker, Redis, RabbitMQ, and PostgreSQL run in containers.

The project is built around one research goal: **compare request scheduling strategies under a realistic queued workload** without building a full GPU-serving stack. InfraQ implements four worker modes, hot-switches them at runtime, and includes a benchmark framework that can run repeatable experiments end to end.

## Maintainer

- `Bowen Wang`

## Why InfraQ

- **MacBook-first**: Ollama runs natively on macOS instead of inside Docker, which is the right tradeoff for Apple GPU access.
- **Asynchronous by default**: requests are accepted immediately, queued through RabbitMQ, and processed by a worker.
- **Fast + durable state**: Redis serves low-latency status polling; PostgreSQL keeps a durable record of requests and benchmark runs.
- **Research-oriented**: approximates continuous batching and prefix caching at the infrastructure layer so the strategies can be compared on consumer Apple hardware.
- **Hot-switchable runtime**: the worker can drain in-flight work, adopt a new strategy, and continue without container restarts.
- **Repeatable benchmarks**: the benchmark launcher handles worker-idle checks, runtime config application, cache clearing, request submission, and result capture for side-by-side experiments.
- **Built-in dashboard**: ships with a browser UI for submission, metrics, and benchmark visualization.

## Architecture

```mermaid
sequenceDiagram
    autonumber
    participant C as Browser / API Client
    participant G as Gateway (Spring Boot)
    participant R as Redis
    participant P as PostgreSQL
    participant Q as RabbitMQ
    participant W as Worker (Python asyncio)
    participant O as Ollama on macOS Host

    C->>G: Submit request or start benchmark
    G->>P: Persist request or benchmark metadata
    G->>R: Write initial status; publish benchmark config when needed
    G->>Q: Enqueue inference work
    G-->>C: Return request ID or benchmark ID

    Q-->>W: Deliver queued request
    W->>R: Read active strategy; update metrics and status
    W->>O: Run inference on cache miss
    O-->>W: Generated response
    W->>R: Write fast status, cache entries, and counters
    W->>P: Persist final result and timings

    C->>G: Poll request status or fetch benchmark results
    G->>R: Read live status and runtime metrics
    G->>P: Read durable history and benchmark summaries
    G-->>C: Return response state and results
```

InfraQ separates admission, queueing, execution, and persistence so local LLM serving can be benchmarked without rebuilding the model runtime itself.

- The `gateway` is the control plane. It exposes the HTTP API and dashboard, accepts requests, stores initial metadata, launches benchmark runs, and returns immediately instead of blocking on inference.
- `RabbitMQ` is the queue boundary. It absorbs bursts and gives the worker a stable backlog to schedule against.
- The `worker` is the scheduling engine. It applies `sequential`, `static`, `continuous`, or `cached`, manages slot usage, and decides whether a request should hit Ollama or return from cache.
- `Ollama` stays on the macOS host so Apple Metal acceleration remains available through `host.docker.internal`.
- `Redis` is the fast-state layer for live request status, prompt-result caching, runtime metrics, and worker configuration.
- `PostgreSQL` is the durable history layer for request records, benchmark runs, and per-request timing data.

During benchmark runs, the gateway first waits for the worker to become idle, writes the requested strategy and slot count to Redis, clears the relevant prompt-cache keys for that run, and only then submits the experiment workload. That is what makes back-to-back comparisons reproducible.

## Core Features

### Inference flow

- `POST /api/v1/infer` accepts a prompt and returns a request ID immediately.
- The worker pulls jobs from RabbitMQ and calls Ollama on the host machine.
- Results are written to Redis for quick polling and PostgreSQL for persistence.

### Scheduling strategies

InfraQ currently includes four worker modes:

| Strategy | What it does | Best use |
| --- | --- | --- |
| `sequential` | One request at a time | Baseline / control |
| `static` | Collect a fixed batch, dispatch it together, then wait for the whole batch to finish | Simple batching experiments |
| `continuous` | Keep a fixed number of active slots and refill each slot immediately on completion | Throughput-oriented testing |
| `cached` | Check a Redis cache before slot acquisition; cache misses fall through to `continuous` | Repeated-prompt workloads |

### Benchmark workloads

InfraQ supports two benchmark prompt mixes:

| Workload | What it measures | Cache behavior |
| --- | --- | --- |
| `unique` | Pure scheduling and queueing behavior under non-repeating prompts | Should stay at or near `0%` cache hits when the run starts cold |
| `repeated` | Cache-aware behavior when a fixed prompt set is reused | Produces partial, not perfect, cache reuse so strategies can still be compared under load |

### Dashboard

The web UI includes:

- **Chat**: send prompts and poll for completion in a chat-style interface
- **Benchmark Lab**: choose scheduling strategy, workload mix, request count, and compare runs
- **Runtime**: see queue depth, completions, cache hits, and effective worker config
- **Recent Runs management**: clear finished benchmark history without touching active runs

## Tech Stack

| Layer | Technology |
| --- | --- |
| Gateway | Spring Boot 3, Java 17 |
| Worker | Python 3.11, `aio-pika`, `aiohttp` |
| Model runtime | Ollama on macOS host |
| Queue | RabbitMQ |
| Cache / fast state | Redis |
| Durable storage | PostgreSQL |
| UI | Static HTML/CSS/JS + Chart.js |

## Quick Start

### 1. Prerequisites

- A **MacBook running macOS**
- **Docker Desktop for Mac**
- **Ollama** installed on the host
- A model pulled locally, for example:

```bash
ollama pull qwen2.5:1.5b
```

If Ollama is not already running:

```bash
ollama serve
```

### 2. Start the stack

From the project root:

```bash
docker compose up --build
```

Then open:

- Dashboard: `http://localhost:8081`
- RabbitMQ UI: `http://localhost:15673`
- PostgreSQL: `localhost:5433`
- Redis: `localhost:6380`

### 3. Submit a request

```bash
curl -X POST http://localhost:8081/api/v1/infer \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Explain what a load balancer does in two sentences.",
    "taskType": "chat",
    "priority": 0
  }'
```

Example response:

```json
{
  "id": "2d2c3b2f-5c8d-4f53-9e77-b51cc5c5036d",
  "status": "QUEUED"
}
```

Check status:

```bash
curl http://localhost:8081/api/v1/infer/<REQUEST_ID>
```

## Running Benchmarks

Start a benchmark:

```bash
curl -X POST http://localhost:8081/api/v1/benchmark \
  -H "Content-Type: application/json" \
  -d '{
    "numRequests": 50,
    "strategy": "continuous",
    "workloadMode": "unique",
    "numSlots": 4
  }'
```

Fetch benchmark results:

```bash
curl http://localhost:8081/api/v1/benchmark/<BENCHMARK_ID>
```

Recommended usage:

- Use `workloadMode = "unique"` when comparing `sequential`, `static`, and `continuous` as scheduling strategies.
- Use `workloadMode = "repeated"` when evaluating `cached`.
- `sequential` is the baseline mode and always runs with exactly `1` slot.
- Run benchmarks **one at a time**. The launcher waits for the worker to become idle before it applies a new runtime config.

> Note
> Benchmark runs clear the prompt-cache entries for the prompts in that run before submission. This avoids cross-run cache contamination and makes workload comparisons more meaningful.

## Evaluation Summary

The report-backed benchmark matrix in this repository ran on a **MacBook Air M1 (8 GB)** using **Ollama + `qwen2.5:1.5b`**, with **100 requests per run** and **5 repeats per condition**.

- On the `unique` workload, scheduling improvements were real but modest. `continuous_8` delivered the best non-cache result at `180 s` average latency and `0.219 req/s`, versus `188 s` and `0.213 req/s` for `sequential_1`.
- On the `repeated` workload, caching was the dominant optimization. `cached_4` reached `0.961 req/s` with a `71%` cache hit rate, cutting average latency from roughly `299 s` to `73 s` relative to non-cached 4-slot runs.
- The slot-count ablation showed that `cached_4` outperformed `cached_8` (`0.961 req/s` vs `0.699 req/s`). More concurrency created more simultaneous misses before the cache warmed, so higher slot counts were not automatically better.
- Across all 45 runs in the report matrix, every run completed successfully.

The corresponding artifacts are included in the repository:

- [Report source](./report/cw3_explanation.tex)
- [Benchmark manifest](./experiment_results/report_matrix_20260411T224526Z/manifest.json)
- [Benchmark summary](./experiment_results/report_matrix_20260411T224526Z/summary.csv)

## Reproducing the Report Experiments

With the stack running and Ollama available on the host, the full report matrix can be launched with:

```bash
python3 scripts/run_report_benchmarks.py
```

The script records:

- `manifest.json`: experiment plan and environment metadata
- `summary.csv`: one row per completed run for quick analysis
- `runs/*.json`: raw per-run data including launch response, progress events, final result, and metric snapshots
- `state.json`: resumable progress for long-running experiment batches

The current report matrix covers:

- `Experiment A`: `unique / 100 / 5 repeats` for `sequential_1`, `static_8`, `continuous_8`, `cached_8`
- `Experiment B`: `repeated / 100 / 5 repeats` for `sequential_1`, `static_4`, `continuous_4`, `cached_4`
- `Ablation`: `repeated / 100 / 5 repeats` for `cached_8`

## Configuration

Most runtime configuration is handled through environment variables in [`docker-compose.yml`](./docker-compose.yml).

Important values:

| Variable | Default | Purpose |
| --- | --- | --- |
| `OLLAMA_HOST` | `http://host.docker.internal:11434` | Reach Ollama running on the host Mac |
| `MODEL` | `qwen2.5:1.5b` | Model used by the worker |
| `NUM_CONCURRENT_SLOTS` | `4` | Worker concurrency level |
| `SCHEDULING_STRATEGY` | `continuous` | Worker scheduling mode |
| `ACP_POSTGRES` | `jdbc:postgresql://postgres:5432/infraq` | Gateway database URL |

## Development

### Gateway

```bash
cd gateway
./mvnw test
./mvnw spring-boot:run
```

### Worker

```bash
cd worker
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 worker.py
```

## API Surface

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/v1/infer` | Submit an inference request |
| `GET` | `/api/v1/infer/{id}` | Fetch status / result |
| `GET` | `/api/v1/infer/list` | List recent requests |
| `POST` | `/api/v1/benchmark` | Start a benchmark run |
| `GET` | `/api/v1/benchmark/{id}` | Fetch benchmark results |
| `GET` | `/api/v1/benchmark` | List benchmark history |
| `DELETE` | `/api/v1/benchmark` | Clear finished benchmark history |
| `GET` | `/api/v1/metrics` | Read live counters and queue depth |
| `PUT` | `/api/v1/metrics/config` | Update runtime config stored in Redis |

## Repository Layout

```text
.
├── gateway/          # Spring Boot API + dashboard
├── report/           # Coursework report and methodology write-up
├── scripts/          # Benchmark automation scripts
├── worker/           # Python async worker that talks to Ollama
├── experiment_results/
├── docker-compose.yml
└── init-db.sql       # PostgreSQL schema
```

## Current Limitations

InfraQ is a strong local prototype, but it is not a production serving stack yet.

- There is **no authentication, multi-tenant isolation, or rate limiting**.
- The system uses **single-worker local orchestration**, not distributed scheduling across multiple hosts.
- Ollama inference is **non-streaming** in the current implementation.
- Default database and broker credentials are for **local development only**.
- Benchmark control is **single-run oriented**; concurrent benchmark runs should be avoided.
- The `cached` strategy is a **text-result cache**, not true GPU KV-cache reuse.
- The worker-level cache is **global per model + prompt**, so non-benchmark traffic can still influence ad hoc performance outside controlled experiments.
- On unique workloads, throughput remains bounded by the underlying Ollama token generation rate; scheduling can reduce queue wait, but it cannot create extra model capacity.

## Validation

The current repository has been minimally validated with:

- `cd gateway && ./mvnw test`
- `cd worker && python3 -m py_compile worker.py`
- `node --check gateway/src/main/resources/static/app.js`
- `docker compose config -q`

## License

MIT
