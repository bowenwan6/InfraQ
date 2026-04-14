#!/usr/bin/env python3
"""Trimmed runner: Experiment A only, 3 repeats, records results under a new
timestamped directory.  Uses the existing run_report_benchmarks machinery.
"""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import run_report_benchmarks as base


def build_matrix_expa_only() -> list[base.BenchmarkRunSpec]:
    """Experiment A only, 3 repeats, 4 strategies."""
    specs: list[base.BenchmarkRunSpec] = []
    order = 1
    configs = [
        ("sequential", 1),
        ("static", 8),
        ("continuous", 8),
        ("cached", 8),
    ]
    for repeat_index in range(1, 4):      # 3 repeats (down from 5)
        for strategy, num_slots in configs:
            specs.append(
                base.BenchmarkRunSpec(
                    order_index=order,
                    phase="experiment_a_parallel4",
                    workload_mode="unique",
                    num_requests=100,
                    strategy=strategy,
                    num_slots=num_slots,
                    repeat_index=repeat_index,
                )
            )
            order += 1
    return specs


def main() -> int:
    base.build_matrix = build_matrix_expa_only

    sys.argv = [
        "run_expa_parallel4.py",
        "--base-url", "http://localhost:8081",
        "--output-dir",
        f"experiment_results/expa_parallel4_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
        "--clear-history-first",
    ]
    return base.main() if hasattr(base, "main") else _run_inline()


def _run_inline() -> int:
    """Fallback entry — replicate base.__main__ logic."""
    args = base.parse_args()
    output_dir = base.Path(args.output_dir) if args.output_dir else base.default_output_dir()
    paths = base.ensure_dirs(output_dir)
    state = base.load_state(paths["state"])
    specs = base.build_matrix()
    base.write_manifest(paths["manifest"], specs, args)
    base.init_summary(paths["summary_csv"])
    base.maybe_clear_history(args.base_url, args.clear_history_first)
    return base.execute(specs, paths, state, args)


if __name__ == "__main__":
    raise SystemExit(main())
