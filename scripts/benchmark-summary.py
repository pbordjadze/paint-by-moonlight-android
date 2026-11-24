#!/usr/bin/env python3
"""Prints the macrobenchmarks' results (Benchmarks, in baselineprofile/) as a Markdown table: each
test's median and P90 of its metrics, cold start in ms and the painting's frames in ms.

    scripts/benchmark-summary.py <benchmarkData.json>…
"""
import json
import sys

METRICS = {
    "timeToInitialDisplayMs": "time to first frame",
    "timeToFullDisplayMs": "time to full display",
    "frameDurationCpuMs": "frame CPU time",
    "frameOverrunMs": "frame overrun",
}


def percentile(values, q):
    return values[min(len(values) - 1, int(q * len(values)))]


def main(paths):
    rows = []
    device = ""
    for path in paths:
        with open(path) as f:
            data = json.load(f)
        build = data.get("context", {}).get("build", {})
        device = f"{build.get('model', '?')}, API {build.get('version', {}).get('sdk', '?')}"
        for bench in data.get("benchmarks", []):
            for key, values in {**bench.get("metrics", {}), **bench.get("sampledMetrics", {})}.items():
                if key not in METRICS:
                    continue
                runs = sorted(r for run in values.get("runs", []) for r in (run if isinstance(run, list) else [run]))
                if not runs:
                    continue
                rows.append((bench["name"], METRICS[key], percentile(runs, 0.5), percentile(runs, 0.9)))
    print(f"### Macrobenchmarks ({device})\n")
    print("| test | metric | median ms | P90 ms |")
    print("| --- | --- | ---: | ---: |")
    for name, metric, p50, p90 in rows:
        print(f"| {name} | {metric} | {p50:.1f} | {p90:.1f} |")


if __name__ == "__main__":
    main(sys.argv[1:])
