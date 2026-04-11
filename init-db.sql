-- InfraQ: Parallel LLM Inference Gateway
-- Database schema

CREATE SCHEMA IF NOT EXISTS infraq;

-- Core request tracking
CREATE TABLE infraq.inference_requests (
    id              UUID PRIMARY KEY,
    prompt          TEXT NOT NULL,
    task_type       VARCHAR(50) DEFAULT 'chat',
    priority        INT DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    worker_id       VARCHAR(50),
    submitted_at    BIGINT NOT NULL,
    started_at      BIGINT,
    completed_at    BIGINT,
    queue_wait_ms   INT,
    inference_ms    INT,
    total_latency_ms INT,
    cache_hit       BOOLEAN DEFAULT FALSE,
    error_message   TEXT
);

-- Store outputs separately (can be large)
CREATE TABLE infraq.inference_results (
    request_id      UUID PRIMARY KEY REFERENCES infraq.inference_requests(id),
    output          TEXT NOT NULL,
    model           VARCHAR(100),
    tokens_in       INT,
    tokens_out      INT,
    created_at      BIGINT DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

-- Benchmark runs
CREATE TABLE infraq.benchmark_runs (
    id              UUID PRIMARY KEY,
    label           VARCHAR(200),
    strategy        VARCHAR(50),
    num_slots       INT,
    total_requests  INT,
    completed       INT DEFAULT 0,
    avg_latency_ms  DOUBLE PRECISION,
    p50_latency_ms  DOUBLE PRECISION,
    p95_latency_ms  DOUBLE PRECISION,
    p99_latency_ms  DOUBLE PRECISION,
    throughput_rps  DOUBLE PRECISION,
    cache_hit_rate  DOUBLE PRECISION,
    avg_queue_wait  DOUBLE PRECISION,
    started_at      BIGINT,
    finished_at     BIGINT,
    status          VARCHAR(20) DEFAULT 'RUNNING'
);

-- Individual request timing within a benchmark
CREATE TABLE infraq.benchmark_requests (
    id              UUID PRIMARY KEY,
    benchmark_id    UUID REFERENCES infraq.benchmark_runs(id),
    request_id      UUID REFERENCES infraq.inference_requests(id),
    latency_ms      INT,
    queue_wait_ms   INT,
    inference_ms    INT,
    cache_hit       BOOLEAN
);

CREATE INDEX idx_req_status ON infraq.inference_requests(status);
CREATE INDEX idx_req_submitted ON infraq.inference_requests(submitted_at DESC);
CREATE INDEX idx_bench_req_bench ON infraq.benchmark_requests(benchmark_id);
