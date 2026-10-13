#!/usr/bin/env python3
"""AgentRelease Lab — pilot-scale load test (standard library only).

PROPOSED PILOT TARGET — not a measured production capacity claim.
This script defines a *proposed* representative workload and measures what
actually happens when it runs. Any numbers it prints describe the run it
just performed in the environment it ran in; they are not production SLOs
and must not be quoted as capacity the system "achieves".

Workload (all fixture-labeled, deterministic, zero provider cost):
  1. API reads:  /api/health, /api/datasets, /api/runbooks,
     /api/service-status, /api/release-decisions
  2. Fixture-mode eval submissions: create eval run (mode=fixture) ->
     ingest a small trace-event batch -> finish the run. Each submission is
     tagged with batch_id "load-test-<timestamp>" so load-test data is
     identifiable and deletable. Run this against a disposable or pilot
     database, not against evidence you need to keep.

Bounded behavior (documented, enforced by the services):
  - Worker queue: bounded with atomic enqueue dedup; processing leases and
    abandoned-job recovery; per-trial completion records prevent duplicate
    mutations on retry.
  - This script bounds itself with --concurrency and --requests; it applies
    backpressure by never having more than --concurrency requests in flight.

Usage:
    python3 load/load_test.py [--base-url URL] [--api-key KEY]
        [--concurrency N] [--requests N] [--eval-submissions N]
        [--timeout SECS] [--out FILE]

Output: JSON to stdout (or --out) with throughput, p50/p95/p99 latency,
error counts, status-code breakdown, and an environment block.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import json
import os
import platform as platform_mod
import statistics
import sys
import time
import urllib.error
import urllib.request

READ_PATHS = [
    "/api/health",
    "/api/datasets",
    "/api/runbooks",
    "/api/service-status",
    "/api/release-decisions",
]


def req(method: str, url: str, api_key: str, body: dict | None, timeout: float) -> tuple[int, float, str]:
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if url.rstrip("/").endswith("/health") is False:
        r.add_header("X-API-Key", api_key)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            payload = resp.read()
            return resp.status, (time.perf_counter() - t0) * 1000.0, ""
    except urllib.error.HTTPError as e:
        return e.code, (time.perf_counter() - t0) * 1000.0, f"http_{e.code}"
    except Exception as e:  # connection errors, timeouts
        return 0, (time.perf_counter() - t0) * 1000.0, f"{type(e).__name__}"


def percentile(xs: list[float], p: float) -> float:
    if not xs:
        return 0.0
    s = sorted(xs)
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def summarize(name: str, results: list[tuple[int, float, str]]) -> dict:
    lat = [ms for _, ms, _ in results]
    codes: dict[str, int] = {}
    err_kinds: dict[str, int] = {}
    for status, _, err in results:
        codes[str(status)] = codes.get(str(status), 0) + 1
        if err:
            err_kinds[err] = err_kinds.get(err, 0) + 1
    ok = sum(1 for status, _, _ in results if 200 <= status < 300)
    return {
        "name": name,
        "requests": len(results),
        "ok": ok,
        "errors": len(results) - ok,
        "error_kinds": err_kinds,
        "status_codes": codes,
        "latency_ms": {
            "p50": round(percentile(lat, 50), 2),
            "p95": round(percentile(lat, 95), 2),
            "p99": round(percentile(lat, 99), 2),
            "max": round(max(lat) if lat else 0.0, 2),
            "mean": round(statistics.fmean(lat) if lat else 0.0, 2),
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--api-key", default="arl-acme-admin-demo",
                    help="seeded demo key; use a pilot credential against a pilot deployment")
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--requests", type=int, default=100,
                    help="total API read requests (spread across read paths)")
    ap.add_argument("--eval-submissions", type=int, default=10,
                    help="fixture-mode eval submissions (create+trace+finish)")
    ap.add_argument("--timeout", type=float, default=15.0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    base = args.base_url.rstrip("/")
    batch = f"load-test-{datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')}"

    # Resolve an agent version for eval submissions (reuse existing when possible).
    status, _, err = req("GET", base + "/api/health", args.api_key, None, args.timeout)
    if status != 200:
        print(f"platform not reachable at {base}/api/health: {err or status}", file=sys.stderr)
        return 2
    status, _, _ = req("GET", base + "/api/agent-versions", args.api_key, None, args.timeout)
    version_id = None
    if status == 200:
        # re-fetch body to parse (req discards it); do a direct fetch
        r = urllib.request.Request(base + "/api/agent-versions", method="GET")
        r.add_header("X-API-Key", args.api_key)
        with urllib.request.urlopen(r, timeout=args.timeout) as resp:
            versions = json.loads(resp.read().decode())
        if versions:
            version_id = versions[0]["id"]
    if version_id is None:
        status, _, err = req("POST", base + "/api/agent-versions", args.api_key,
                             {"name": "load-test-version", "modelId": "fixture-1.0",
                              "prompt": "load test placeholder", "config": {}}, args.timeout)
        if status not in (200, 201):
            print(f"could not register agent version: {err or status}", file=sys.stderr)
            return 2
        r = urllib.request.Request(base + "/api/agent-versions", method="GET")
        r.add_header("X-API-Key", args.api_key)
        with urllib.request.urlopen(r, timeout=args.timeout) as resp:
            version_id = json.loads(resp.read().decode())[0]["id"]

    t_start = time.perf_counter()
    read_results: list[tuple[int, float, str]] = []
    eval_results: list[tuple[int, float, str]] = []

    def do_read(i: int) -> tuple[int, float, str]:
        path = READ_PATHS[i % len(READ_PATHS)]
        needs_key = not path.endswith("/health")
        return req("GET", base + path, args.api_key if needs_key else "", None, args.timeout)

    def do_eval_submission(i: int) -> tuple[int, float, str]:
        t0 = time.perf_counter()
        errs: list[str] = []

        def post_json(method: str, path: str, body: dict) -> tuple[int, dict | None, str]:
            data = json.dumps(body).encode()
            r = urllib.request.Request(base + path, data=data, method=method)
            r.add_header("X-API-Key", args.api_key)
            r.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(r, timeout=args.timeout) as resp:
                    raw = resp.read().decode()
                    return resp.status, (json.loads(raw) if raw else None), ""
            except urllib.error.HTTPError as e:
                return e.code, None, f"http_{e.code}"
            except Exception as e:
                return 0, None, type(e).__name__

        s, created, e = post_json("POST", "/api/eval-runs",
                                 {"agentVersionId": version_id, "datasetId": "it-service-desk-v1",
                                  "scenarioId": "load-probe", "trialIndex": i, "mode": "fixture",
                                  "chaos": {}, "batchId": batch})
        if s not in (200, 201) or not created or "id" not in created:
            return s, (time.perf_counter() - t0) * 1000.0, e or f"create_{s}"
        run_id = created["id"]
        s, _, e = post_json("POST", "/api/traces/events",
                            {"evalRunId": run_id,
                             "events": [{"kind": "note", "name": "load-probe",
                                         "payload": {"i": i, "mode": "fixture"}}]})
        if s not in (200, 201, 204):
            errs.append(e or f"trace_{s}")
        s, _, e = post_json("PATCH", f"/api/eval-runs/{run_id}",
                            {"status": "SUCCEEDED",
                             "metrics": {"task_completed": True, "mode": "fixture",
                                         "tool_calls": 0, "tokens_used": 0,
                                         "estimated_cost_usd": 0.0}})
        if s not in (200, 204):
            errs.append(e or f"finish_{s}")
        ms = (time.perf_counter() - t0) * 1000.0
        return (200 if not errs else 500, ms, ";".join(errs))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        read_results = list(ex.map(do_read, range(args.requests)))
        eval_results = list(ex.map(do_eval_submission, range(args.eval_submissions)))
    elapsed = time.perf_counter() - t_start

    total = len(read_results) + len(eval_results)
    report = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "workload": {
            "label": "PROPOSED PILOT TARGET — not a measured production capacity claim",
            "description": "API reads + fixture-mode eval submissions (create run, ingest trace events, finish run), all mode=fixture",
            "base_url": base,
            "concurrency": args.concurrency,
            "api_read_requests": args.requests,
            "eval_submissions": args.eval_submissions,
            "batch_id": batch,
        },
        "results": {
            "elapsed_seconds": round(elapsed, 2),
            "throughput_rps": round(total / elapsed, 2) if elapsed > 0 else 0.0,
            "api_reads": summarize("api_reads", read_results),
            "eval_submissions": summarize("eval_submissions", eval_results),
        },
        "environment": {
            "python": platform_mod.python_version(),
            "os": f"{platform_mod.system()} {platform_mod.release()}",
            "machine": platform_mod.machine(),
            "cpu_count": os.cpu_count(),
            "target": base,
        },
        "notes": [
            "PROPOSED PILOT TARGET — not a measured production capacity claim. Numbers describe this run only.",
            "Queue/backpressure behavior is enforced by the services (bounded worker queue, atomic enqueue dedup, processing leases, abandoned-job recovery); this script bounds itself with --concurrency and applies backpressure by limiting in-flight requests.",
            "Load-test eval runs are mode=fixture and tagged with the batch_id above; delete that batch afterwards on a shared database.",
            "Recovery-after-overload and sustained resource usage are not measured here; run repeated passes and watch monitoring/alerts.yml signals for those.",
        ],
    }
    out = json.dumps(report, indent=2)
    if args.out:
        with open(args.out, "w") as f:
            f.write(out + "\n")
    else:
        print(out)
    n_err = report["results"]["api_reads"]["errors"] + report["results"]["eval_submissions"]["errors"]
    return 0 if n_err == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
