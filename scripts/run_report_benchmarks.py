#!/usr/bin/env python3
"""Run the InfraQ report benchmark matrix and persist raw results.

This script executes the user-defined benchmark plan against the local gateway,
waits for each run to finish, and saves both summary rows and full raw payloads
for later analysis. Results are written incrementally so the run can be resumed
after an interruption.
"""

from __future__ import annotations

import argparse
import csv
import json
import platform
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error, request


TERMINAL_STATUSES = {"COMPLETED", "FAILED", "TIMED_OUT"}
READY_PHASES = {"READY", "RUNNING"}
SUMMARY_FIELDS = [
    "order_index",
    "run_key",
    "phase",
    "repeat_index",
    "workload_mode",
    "num_requests",
    "strategy",
    "num_slots",
    "launch_status",
    "benchmark_id",
    "label",
    "final_status",
    "completed",
    "total_requests",
    "avg_latency_ms",
    "p50_latency_ms",
    "p95_latency_ms",
    "p99_latency_ms",
    "throughput_rps",
    "cache_hit_rate",
    "avg_queue_wait",
    "started_at_ms",
    "finished_at_ms",
    "elapsed_wall_seconds",
    "launch_requested_strategy",
    "launch_requested_slots",
    "launch_effective_strategy",
    "launch_effective_slots",
    "launch_worker_phase",
    "prelaunch_pending_queue",
    "postrun_pending_queue",
    "result_json",
]


class ApiError(RuntimeError):
    """Raised when the gateway returns a non-successful HTTP response."""

    def __init__(self, status_code: int, message: str, body: Any | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.body = body


@dataclass(frozen=True)
class BenchmarkRunSpec:
    """A single benchmark run in the report matrix."""

    order_index: int
    phase: str
    workload_mode: str
    num_requests: int
    strategy: str
    num_slots: int
    repeat_index: int

    @property
    def run_key(self) -> str:
        """Return a stable identifier for resume support and filenames."""
        return (
            f"{self.order_index:03d}_"
            f"{self.phase}_"
            f"{self.workload_mode}_"
            f"{self.strategy}_"
            f"{self.num_slots}slots_"
            f"r{self.repeat_index:02d}"
        )


def parse_args() -> argparse.Namespace:
    """Parse CLI flags."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default="http://localhost:8081",
        help="Base URL for the InfraQ gateway. Default: %(default)s",
    )
    parser.add_argument(
        "--output-dir",
        default=None,
        help="Directory where raw results are written. Default: experiment_results/report_matrix_<UTC timestamp>",
    )
    parser.add_argument(
        "--idle-timeout-seconds",
        type=int,
        default=900,
        help="Seconds to wait for the worker to become idle before each run.",
    )
    parser.add_argument(
        "--poll-interval-seconds",
        type=float,
        default=2.0,
        help="Seconds between benchmark status polls.",
    )
    parser.add_argument(
        "--idle-poll-interval-seconds",
        type=float,
        default=5.0,
        help="Seconds between worker idle checks.",
    )
    parser.add_argument(
        "--pause-between-runs-seconds",
        type=float,
        default=3.0,
        help="Seconds to sleep after a run before launching the next one.",
    )
    parser.add_argument(
        "--plan-only",
        action="store_true",
        help="Print the benchmark matrix and exit without running it.",
    )
    parser.add_argument(
        "--clear-history-first",
        action="store_true",
        help="Clear completed/failed/timed-out benchmark runs before starting.",
    )
    return parser.parse_args()


def utc_now_iso() -> str:
    """Return the current UTC time as an ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat()


def default_output_dir() -> Path:
    """Build the default results directory."""
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return Path("experiment_results") / f"report_matrix_{stamp}"


def log(message: str) -> None:
    """Print a timestamped progress message."""
    print(f"[{utc_now_iso()}] {message}", flush=True)


def http_json(base_url: str, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    """Send an HTTP request and parse the JSON response."""
    data = None
    headers: dict[str, str] = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = request.Request(f"{base_url.rstrip('/')}{path}", data=data, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=60) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else None
    except error.HTTPError as exc:
        raw_body = exc.read().decode("utf-8")
        parsed_body: Any
        try:
            parsed_body = json.loads(raw_body) if raw_body else None
        except json.JSONDecodeError:
            parsed_body = raw_body or None
        message = raw_body or f"HTTP {exc.code}"
        raise ApiError(exc.code, message, parsed_body) from exc


def ensure_dirs(output_dir: Path) -> dict[str, Path]:
    """Create the output directory tree."""
    run_dir = output_dir / "runs"
    output_dir.mkdir(parents=True, exist_ok=True)
    run_dir.mkdir(parents=True, exist_ok=True)
    return {
        "output_dir": output_dir,
        "run_dir": run_dir,
        "manifest": output_dir / "manifest.json",
        "state": output_dir / "state.json",
        "summary_csv": output_dir / "summary.csv",
    }


def load_state(state_path: Path) -> dict[str, Any]:
    """Load persisted runner state if present."""
    if not state_path.exists():
        return {
            "created_at_utc": utc_now_iso(),
            "updated_at_utc": utc_now_iso(),
            "completed_runs": {},
        }
    return json.loads(state_path.read_text(encoding="utf-8"))


def save_state(state_path: Path, state: dict[str, Any]) -> None:
    """Persist the runner state atomically."""
    state["updated_at_utc"] = utc_now_iso()
    state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")


def write_manifest(manifest_path: Path, specs: list[BenchmarkRunSpec], args: argparse.Namespace) -> None:
    """Write immutable run metadata."""
    if manifest_path.exists():
        return
    manifest = {
        "created_at_utc": utc_now_iso(),
        "base_url": args.base_url,
        "matrix_size": len(specs),
        "machine": {
            "platform": platform.platform(),
            "python": platform.python_version(),
            "hostname": platform.node(),
        },
        "runner": {
            "idle_timeout_seconds": args.idle_timeout_seconds,
            "poll_interval_seconds": args.poll_interval_seconds,
            "idle_poll_interval_seconds": args.idle_poll_interval_seconds,
            "pause_between_runs_seconds": args.pause_between_runs_seconds,
            "clear_history_first": args.clear_history_first,
        },
        "matrix": [asdict(spec) for spec in specs],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def append_summary_row(summary_path: Path, row: dict[str, Any]) -> None:
    """Append one row to the CSV summary."""
    should_write_header = not summary_path.exists()
    with summary_path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=SUMMARY_FIELDS)
        if should_write_header:
            writer.writeheader()
        writer.writerow(row)


def pending_queue_from_metrics(metrics: dict[str, Any]) -> int | None:
    """Return broker queue + worker buffer when available."""
    buffered = int(metrics.get("buffered_messages", 0) or 0)
    raw_queue = metrics.get("queue_depth")
    try:
        broker_queue = int(raw_queue)
    except (TypeError, ValueError):
        return buffered
    if broker_queue < 0:
        return buffered
    return broker_queue + buffered


def wait_for_idle(base_url: str, idle_timeout_seconds: int, poll_seconds: float) -> dict[str, Any]:
    """Wait until the worker reports no active or pending benchmark work."""
    deadline = time.monotonic() + idle_timeout_seconds
    last_signature: tuple[Any, ...] | None = None

    while time.monotonic() < deadline:
        metrics = http_json(base_url, "GET", "/api/v1/metrics")
        pending_queue = pending_queue_from_metrics(metrics)
        active_requests = int(metrics.get("active_requests", 0) or 0)
        phase = str(metrics.get("worker_phase", "UNKNOWN"))
        signature = (phase, pending_queue, active_requests, metrics.get("effective_strategy"), metrics.get("effective_slots"))

        if signature != last_signature:
            log(
                "Worker state:"
                f" phase={phase}"
                f" pending_queue={pending_queue}"
                f" active_requests={active_requests}"
                f" effective={metrics.get('effective_strategy')}/{metrics.get('effective_slots')}"
            )
            last_signature = signature

        if phase in READY_PHASES and active_requests == 0 and (pending_queue in (0, None)):
            return metrics

        time.sleep(poll_seconds)

    raise TimeoutError("Worker did not become idle before the idle timeout expired.")


def launch_benchmark(base_url: str, spec: BenchmarkRunSpec) -> dict[str, Any]:
    """Submit one benchmark launch request."""
    payload = {
        "numRequests": spec.num_requests,
        "strategy": spec.strategy,
        "numSlots": spec.num_slots,
        "workloadMode": spec.workload_mode,
    }
    return http_json(base_url, "POST", "/api/v1/benchmark", payload)


def poll_benchmark(base_url: str, benchmark_id: str, poll_seconds: float) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Poll a benchmark until it reaches a terminal state."""
    progress_events: list[dict[str, Any]] = []
    last_completed: int | None = None
    last_status: str | None = None

    while True:
        run = http_json(base_url, "GET", f"/api/v1/benchmark/{benchmark_id}")
        completed = int(run.get("completed", 0) or 0)
        status = str(run.get("status", "UNKNOWN"))

        if completed != last_completed or status != last_status:
            event = {
                "observed_at_utc": utc_now_iso(),
                "status": status,
                "completed": completed,
                "total_requests": run.get("total_requests"),
            }
            progress_events.append(event)
            log(
                f"Benchmark {benchmark_id[:8]} status={status}"
                f" progress={completed}/{run.get('total_requests', '?')}"
            )
            last_completed = completed
            last_status = status

        if status in TERMINAL_STATUSES:
            return run, progress_events

        time.sleep(poll_seconds)


def build_matrix() -> list[BenchmarkRunSpec]:
    """Construct the fixed benchmark matrix requested by the user."""
    specs: list[BenchmarkRunSpec] = []
    order = 1

    def add_block(
        phase: str,
        workload_mode: str,
        num_requests: int,
        configs: list[tuple[str, int]],
        repeats: int,
    ) -> None:
        nonlocal order
        for repeat_index in range(1, repeats + 1):
            for strategy, num_slots in configs:
                specs.append(
                    BenchmarkRunSpec(
                        order_index=order,
                        phase=phase,
                        workload_mode=workload_mode,
                        num_requests=num_requests,
                        strategy=strategy,
                        num_slots=num_slots,
                        repeat_index=repeat_index,
                    )
                )
                order += 1

    add_block(
        phase="experiment_a",
        workload_mode="unique",
        num_requests=100,
        configs=[
            ("sequential", 1),
            ("static", 8),
            ("continuous", 8),
            ("cached", 8),
        ],
        repeats=5,
    )
    add_block(
        phase="experiment_b",
        workload_mode="repeated",
        num_requests=100,
        configs=[
            ("sequential", 1),
            ("static", 4),
            ("continuous", 4),
            ("cached", 4),
        ],
        repeats=5,
    )
    add_block(
        phase="ablation_cached_8",
        workload_mode="repeated",
        num_requests=100,
        configs=[("cached", 8)],
        repeats=5,
    )

    return specs


def print_plan(specs: list[BenchmarkRunSpec]) -> None:
    """Print the matrix to stdout."""
    for spec in specs:
        print(
            f"{spec.order_index:03d} "
            f"{spec.phase} "
            f"workload={spec.workload_mode} "
            f"requests={spec.num_requests} "
            f"strategy={spec.strategy} "
            f"slots={spec.num_slots} "
            f"repeat={spec.repeat_index}"
        )


def maybe_clear_history(base_url: str, enabled: bool) -> None:
    """Optionally clear old completed benchmark history before the run starts."""
    if not enabled:
        return
    payload = http_json(base_url, "DELETE", "/api/v1/benchmark")
    log(f"Cleared benchmark history: {payload}")


def save_run_result(run_path: Path, data: dict[str, Any]) -> None:
    """Write one run's raw data bundle."""
    run_path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def summary_row(
    spec: BenchmarkRunSpec,
    launch: dict[str, Any],
    final_run: dict[str, Any],
    pre_metrics: dict[str, Any],
    post_metrics: dict[str, Any],
    wall_seconds: float,
    result_path: Path,
) -> dict[str, Any]:
    """Build the CSV summary row."""
    return {
        "order_index": spec.order_index,
        "run_key": spec.run_key,
        "phase": spec.phase,
        "repeat_index": spec.repeat_index,
        "workload_mode": spec.workload_mode,
        "num_requests": spec.num_requests,
        "strategy": spec.strategy,
        "num_slots": spec.num_slots,
        "launch_status": launch.get("status"),
        "benchmark_id": launch.get("benchmark_id"),
        "label": final_run.get("label"),
        "final_status": final_run.get("status"),
        "completed": final_run.get("completed"),
        "total_requests": final_run.get("total_requests"),
        "avg_latency_ms": final_run.get("avg_latency_ms"),
        "p50_latency_ms": final_run.get("p50_latency_ms"),
        "p95_latency_ms": final_run.get("p95_latency_ms"),
        "p99_latency_ms": final_run.get("p99_latency_ms"),
        "throughput_rps": final_run.get("throughput_rps"),
        "cache_hit_rate": final_run.get("cache_hit_rate"),
        "avg_queue_wait": final_run.get("avg_queue_wait"),
        "started_at_ms": final_run.get("started_at"),
        "finished_at_ms": final_run.get("finished_at"),
        "elapsed_wall_seconds": round(wall_seconds, 3),
        "launch_requested_strategy": launch.get("requested_strategy"),
        "launch_requested_slots": launch.get("requested_slots"),
        "launch_effective_strategy": launch.get("effective_strategy"),
        "launch_effective_slots": launch.get("effective_slots"),
        "launch_worker_phase": launch.get("worker_phase"),
        "prelaunch_pending_queue": pending_queue_from_metrics(pre_metrics),
        "postrun_pending_queue": pending_queue_from_metrics(post_metrics),
        "result_json": str(result_path),
    }


def run_matrix(args: argparse.Namespace) -> int:
    """Run the full benchmark matrix."""
    specs = build_matrix()
    if args.plan_only:
        print_plan(specs)
        return 0

    output_dir = Path(args.output_dir) if args.output_dir else default_output_dir()
    paths = ensure_dirs(output_dir)
    state = load_state(paths["state"])
    write_manifest(paths["manifest"], specs, args)

    completed_runs: dict[str, Any] = state.setdefault("completed_runs", {})
    log(f"Results directory: {paths['output_dir']}")
    log(f"Total planned runs: {len(specs)}")

    maybe_clear_history(args.base_url, args.clear_history_first)

    for spec in specs:
        if spec.run_key in completed_runs:
            log(f"Skipping completed run: {spec.run_key}")
            continue

        log(
            f"Starting run {spec.order_index}/{len(specs)}: "
            f"{spec.phase} workload={spec.workload_mode} "
            f"strategy={spec.strategy} slots={spec.num_slots} repeat={spec.repeat_index}"
        )

        pre_metrics = wait_for_idle(
            args.base_url,
            idle_timeout_seconds=args.idle_timeout_seconds,
            poll_seconds=args.idle_poll_interval_seconds,
        )
        launch_started = time.monotonic()
        launch = launch_benchmark(args.base_url, spec)
        benchmark_id = str(launch["benchmark_id"])
        final_run, progress_events = poll_benchmark(
            args.base_url,
            benchmark_id=benchmark_id,
            poll_seconds=args.poll_interval_seconds,
        )
        wall_seconds = time.monotonic() - launch_started
        post_metrics = http_json(args.base_url, "GET", "/api/v1/metrics")

        run_bundle = {
            "captured_at_utc": utc_now_iso(),
            "spec": asdict(spec),
            "prelaunch_metrics": pre_metrics,
            "launch_response": launch,
            "progress_events": progress_events,
            "final_result": final_run,
            "postrun_metrics": post_metrics,
            "elapsed_wall_seconds": round(wall_seconds, 3),
        }

        result_path = paths["run_dir"] / f"{spec.run_key}.json"
        save_run_result(result_path, run_bundle)
        row = summary_row(spec, launch, final_run, pre_metrics, post_metrics, wall_seconds, result_path)
        append_summary_row(paths["summary_csv"], row)

        completed_runs[spec.run_key] = {
            "benchmark_id": benchmark_id,
            "final_status": final_run.get("status"),
            "result_json": str(result_path),
        }
        save_state(paths["state"], state)

        log(
            f"Finished {spec.run_key}: "
            f"status={final_run.get('status')} "
            f"avg_latency_ms={final_run.get('avg_latency_ms')} "
            f"throughput_rps={final_run.get('throughput_rps')} "
            f"cache_hit_rate={final_run.get('cache_hit_rate')}"
        )
        time.sleep(args.pause_between_runs_seconds)

    log("Benchmark matrix completed.")
    return 0


def main() -> int:
    """Program entry point."""
    args = parse_args()
    try:
        return run_matrix(args)
    except KeyboardInterrupt:
        log("Interrupted by user.")
        return 130
    except ApiError as exc:
        log(f"Gateway API error ({exc.status_code}): {exc}")
        if exc.body is not None:
            log(f"Response body: {exc.body}")
        return 1
    except Exception as exc:  # pragma: no cover - defensive runner guard
        log(f"Fatal error: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
