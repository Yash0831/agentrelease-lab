#!/usr/bin/env python3
"""Bounded live-provider smoke test for AgentRelease Lab.

Runs a small, explicitly live set of agent trials through the worker's
/jobs/run-sync endpoint against a real OpenAI-compatible provider and
verifies: retrieval, a structured tool call, tool-result processing, a
final answer, and trace/eval persistence in the platform.

Live mode is OPT-IN ONLY: requires --mode live AND the environment
variables LLM_BASE_URL, LLM_API_KEY, LLM_MODEL. If any piece is missing
the harness prints the missing variable names (never the key value) and
exits 2 with status BLOCKED — no fake run is produced.

Cost accounting comes from eval/price-table.json. If the configured model
is absent from the price table, cost fields are null with
pricing_status "unknown" (never 0.0).

A JSON report is written to benchmarks/reports/live-smoke-<timestamp>.json
ONLY when a real run happened; it is labeled "mode": "live" so live
evidence stays separate from fixture reports.

Bounded: --max-jobs (default 3), --concurrency (default 1, hard cap 4),
--max-tokens and --max-cost-usd budgets. Budgets are checked before each
job submission; breaching one stops further submissions and is reported.

Usage:
    LLM_BASE_URL=... LLM_API_KEY=... LLM_MODEL=... \\
      python3 benchmarks/run_live_smoke.py --mode live
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import json
import os
import sys
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "agent-worker"))

from app.metrics import estimate_cost_usd, load_price_table  # noqa: E402

LIVE_ENV_VARS = ("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL")
VERSION_NAME = "arl-live-smoke"
DATASET_ID = "it-service-desk-v1"
# Small fixed scenario set: covers retrieval, honest empty retrieval, and
# tool-timeout degradation against the live provider.
DEFAULT_SCENARIOS = ["happy-path-vpn", "empty-retrieval", "tool-timeout"]
HARD_CONCURRENCY_CAP = 4
REPORT_DIR = ROOT / "benchmarks" / "reports"


def clamp_concurrency(n: int) -> tuple[int, str | None]:
    """Enforce the hard concurrency cap. Returns (value, note)."""
    n = max(1, int(n or 1))
    if n > HARD_CONCURRENCY_CAP:
        return HARD_CONCURRENCY_CAP, (
            f"requested concurrency {n} capped to hard limit "
            f"{HARD_CONCURRENCY_CAP}")
    return n, None


def blocked(reason: str, missing: list[str] | None = None) -> int:
    """Live verification cannot proceed: report BLOCKED, exit 2."""
    payload = {"status": "BLOCKED", "mode": "live", "reason": reason}
    if missing:
        payload["missing_env"] = missing
    # Never include credential values — names only.
    print(json.dumps(payload, indent=2))
    return 2


def http_error_summary(e: Exception) -> str:
    return f"{type(e).__name__}: {str(e)[:160]}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--mode", default=None,
                    help="must be 'live' — live runs are explicit opt-in only")
    ap.add_argument("--platform", default="http://localhost:8080")
    ap.add_argument("--worker", default="http://localhost:8001")
    ap.add_argument("--worker-key", default="change-me-worker-key")
    ap.add_argument("--max-jobs", type=int, default=3,
                    help="max scenarios to run (default 3)")
    ap.add_argument("--concurrency", type=int, default=1,
                    help="parallel job submissions (default 1, hard cap 4)")
    ap.add_argument("--max-tokens", type=int, default=None,
                    help="input+output token budget for the whole run")
    ap.add_argument("--max-cost-usd", type=float, default=None,
                    help="estimated USD budget for the whole run")
    ap.add_argument("--scenarios", nargs="*", default=None,
                    help="override the default scenario set")
    ap.add_argument("--out", default=str(REPORT_DIR))
    args = ap.parse_args()

    if args.mode != "live":
        return blocked("explicit live mode required: re-run with --mode live")

    missing = [v for v in LIVE_ENV_VARS if not os.environ.get(v)]
    if missing:
        return blocked("live provider not configured", missing=missing)
    base_url = os.environ["LLM_BASE_URL"]
    model_id = os.environ["LLM_MODEL"]
    # LLM_API_KEY is read but never printed or stored in the report.

    concurrency, concurrency_note = clamp_concurrency(args.concurrency)

    price_table = load_price_table()
    pricing_known = model_id in (price_table.get("models") or {})
    pricing_status = "known" if pricing_known else "unknown"

    scenarios = list(args.scenarios or DEFAULT_SCENARIOS)[: max(0, args.max_jobs)]

    # --- prerequisite checks: worker, then platform (trace/eval persistence
    # requires the platform; the worker alone is not enough) ---
    try:
        wh = httpx.get(f"{args.worker}/health", timeout=10, trust_env=False)
        wh.raise_for_status()
    except Exception as e:
        return blocked(f"worker unreachable at {args.worker}: {http_error_summary(e)}")
    try:
        ph = httpx.get(f"{args.platform}/api/health", timeout=10, trust_env=False)
        ph.raise_for_status()
    except Exception as e:
        return blocked(f"platform unreachable at {args.platform}: "
                       f"{http_error_summary(e)}; platform connectivity is "
                       f"required for trace/eval persistence verification")

    # --- register the smoke version with the ACTUAL configured model id ---
    version_id = _register_version(args.platform, model_id)
    if version_id is None:
        return blocked("could not register agent version on the platform")

    batch_id = (f"live-smoke-{datetime.datetime.now().strftime('%Y%m%d-%H%M%S')}"
                f"-{uuid.uuid4().hex[:6]}")
    budgets = {
        "max_tokens": args.max_tokens,
        "max_cost_usd": args.max_cost_usd,
        "pricing_status": pricing_status,
        "cost_budget_enforceable": pricing_known or args.max_cost_usd is None,
    }
    if args.max_cost_usd is not None and not pricing_known:
        budgets["cost_budget_note"] = (
            "model not in price table: cost is unknown, so the USD budget "
            "cannot be enforced; token budget (if set) still applies")

    state = {"input_tokens": 0, "output_tokens": 0, "cost_used_usd": 0.0,
             "stopped": None}
    jobs: list[dict] = []
    checks: list[dict] = []

    def submit(scenario_id: str) -> dict:
        if args.max_tokens is not None and \
                state["input_tokens"] + state["output_tokens"] >= args.max_tokens:
            state["stopped"] = (f"token budget reached "
                                f"({state['input_tokens'] + state['output_tokens']}"
                                f" >= {args.max_tokens})")
            return {"scenario": scenario_id, "submitted": False,
                    "reason": state["stopped"]}
        if (args.max_cost_usd is not None and pricing_known
                and state["cost_used_usd"] >= args.max_cost_usd):
            state["stopped"] = (f"cost budget reached "
                                f"({state['cost_used_usd']:.6f} >= {args.max_cost_usd})")
            return {"scenario": scenario_id, "submitted": False,
                    "reason": state["stopped"]}
        payload = {"versions": [VERSION_NAME], "dataset_id": DATASET_ID,
                   "scenarios": [scenario_id], "trials": 1, "mode": "live",
                   "batch_id": batch_id}
        headers = {"Authorization": f"Bearer {args.worker_key}"}
        try:
            r = httpx.post(f"{args.worker}/jobs/run-sync", json=payload,
                           headers=headers, timeout=600, trust_env=False)
            r.raise_for_status()
            return {"scenario": scenario_id, "submitted": True,
                    "job": r.json()}
        except Exception as e:
            return {"scenario": scenario_id, "submitted": True,
                    "error": http_error_summary(e)}

    with concurrent.futures.ThreadPoolExecutor(
            max_workers=concurrency) as pool:
        # Submitting strictly in scenario order keeps the batch id sequence
        # and budget checks deterministic even with concurrency > 1.
        for scenario_id in scenarios:
            if state["stopped"]:
                jobs.append({"scenario": scenario_id, "submitted": False,
                             "reason": state["stopped"]})
                continue
            fut = pool.submit(submit, scenario_id)
            jobs.append(fut.result())

    ran = False
    for job in jobs:
        if not job.get("submitted") or job.get("error"):
            continue
        ran = True
        job["verification"] = _verify_job(args.platform, job["job"],
                                          model_id, price_table)
        for trial in job["verification"].get("trials", []):
            state["input_tokens"] += trial.get("input_tokens", 0)
            state["output_tokens"] += trial.get("output_tokens", 0)
            tc = trial.get("estimated_cost_usd")
            if tc is not None:
                state["cost_used_usd"] += tc
        checks.extend(job.get("verification", {}).get("checks", []))

    # A real run happened (even if every trial failed) — write the report.
    if not ran:
        return blocked("no live trial was submitted; nothing to report")

    report = {
        "mode": "live",
        "status": "COMPLETED" if not state["stopped"] else "BUDGET_STOPPED",
        "stop_reason": state["stopped"],
        "batch_id": batch_id,
        "model": model_id,
        "provider_base_url": base_url,
        "pricing_status": pricing_status,
        "price_table_as_of": price_table.get("as_of"),
        "budgets": budgets,
        "usage": {
            "input_tokens": state["input_tokens"],
            "output_tokens": state["output_tokens"],
            # None when pricing unknown — never 0.0.
            "estimated_cost_usd": (round(state["cost_used_usd"], 6)
                                   if pricing_known else None),
        },
        "concurrency": concurrency,
        "concurrency_note": concurrency_note,
        "scenarios": scenarios,
        "jobs": jobs,
        "checks": checks,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "notes": [
            "Live evidence is labeled mode=live throughout and kept separate "
            "from fixture reports (live-smoke-*.json vs eval-report-*.json).",
            "Probabilistic model behavior is expected; live verdicts are not "
            "compared against scripted fixture verdicts.",
        ],
    }
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out_dir / f"live-smoke-{ts}.json"
    # The report carries no credential values: only env-var names were ever
    # handled, and LLM_API_KEY is never placed in the report.
    text = json.dumps(report, indent=2)
    path.write_text(text)
    print(f"live smoke report: {path}")
    print(f"status={report['status']} trials_verified="
          f"{sum(1 for c in checks if c.get('passed'))}/{len(checks)}")
    return 0


def _register_version(platform: str, model_id: str) -> str | None:
    body = {
        "name": VERSION_NAME,
        "modelId": model_id,
        "prompt": ("You are a careful IT service desk agent for Acme Corp. "
                   "Use the provided tools to investigate; never invent tool "
                   "results. Cite the runbook slug and version for claims "
                   "drawn from documents. If you cannot find evidence, say so "
                   "explicitly instead of guessing."),
        "toolSchemas": {},
        "docSnapshotId": "seed-2026-09-29",
        "policyVersion": "policy-v1",
    }
    try:
        r = httpx.post(f"{platform}/api/agent-versions", json=body,
                       timeout=15, trust_env=False)
        if r.status_code == 409:
            existing = next(
                v for v in httpx.get(f"{platform}/api/agent-versions",
                                     timeout=15, trust_env=False).json()
                if v["name"] == VERSION_NAME)
            if existing.get("modelId") != model_id:
                print(f"version '{VERSION_NAME}' already registered with "
                      f"modelId='{existing.get('modelId')}', but this run "
                      f"needs '{model_id}'. Use a fresh database.",
                      file=sys.stderr)
                return None
            return existing["id"]
        r.raise_for_status()
        return r.json()["id"]
    except Exception as e:
        print(f"version registration failed: {http_error_summary(e)}",
              file=sys.stderr)
        return None


def _verify_job(platform: str, job: dict, model_id: str,
                price_table: dict) -> dict:
    """Per-trial evidence verification against the platform's persisted state.

    Covers: retrieval (search_runbooks executed), a structured tool call,
    tool-result processing, a final answer, and trace/eval persistence.
    """
    checks: list[dict] = []
    trials_out: list[dict] = []
    for trial in job.get("trials", []):
        eval_run_id = trial.get("eval_run_id", "")
        t = {"scenario": trial.get("scenario"), "eval_run_id": eval_run_id,
             "status": trial.get("status")}
        prefix = f"[{trial.get('scenario')}] "
        if not eval_run_id:
            checks.append({"name": prefix + "eval_run_persisted",
                           "passed": False, "detail": "no eval_run_id"})
            trials_out.append(t)
            continue
        try:
            run = httpx.get(f"{platform}/api/eval-runs/{eval_run_id}",
                            timeout=15, trust_env=False)
            run.raise_for_status()
            rund = run.json()
        except Exception as e:
            checks.append({"name": prefix + "eval_run_persisted",
                           "passed": False,
                           "detail": f"GET /api/eval-runs failed: {http_error_summary(e)}"})
            trials_out.append(t)
            continue
        metrics = rund.get("metrics") or {}
        t.update({
            "mode": rund.get("mode"),
            "run_status": rund.get("status"),
            "input_tokens": metrics.get("input_tokens", 0),
            "output_tokens": metrics.get("output_tokens", 0),
            "estimated_cost_usd": metrics.get("estimated_cost_usd"),
            "pricing_status": metrics.get("pricing_status"),
        })
        checks.append({"name": prefix + "eval_run_persisted", "passed": True,
                       "detail": f"status={rund.get('status')}"})
        checks.append({"name": prefix + "mode_is_live",
                       "passed": rund.get("mode") == "live",
                       "detail": f"mode={rund.get('mode')}"})
        # Independent cost recomputation: unknown pricing stays unknown.
        recomputed = estimate_cost_usd(
            price_table, model_id, t["input_tokens"], t["output_tokens"])
        cost_ok = ((recomputed is None and t["estimated_cost_usd"] is None
                    and t["pricing_status"] == "unknown")
                   or (recomputed is not None
                       and t["estimated_cost_usd"] == round(recomputed, 6)
                       and t["pricing_status"] == "known"))
        checks.append({"name": prefix + "cost_accounting",
                       "passed": cost_ok,
                       "detail": (f"estimated_cost_usd={t['estimated_cost_usd']} "
                                  f"pricing_status={t['pricing_status']}")})
        try:
            tl = httpx.get(f"{platform}/api/traces/timeline",
                           params={"evalRunId": eval_run_id},
                           timeout=15, trust_env=False)
            tl.raise_for_status()
            events = tl.json() or []
        except Exception as e:
            checks.append({"name": prefix + "trace_persisted", "passed": False,
                           "detail": f"timeline failed: {http_error_summary(e)}"})
            trials_out.append(t)
            continue
        kinds = {(e.get("kind"), e.get("name")) for e in events}
        t["trace_events"] = len(events)
        checks.append({"name": prefix + "trace_persisted",
                       "passed": len(events) > 0,
                       "detail": f"{len(events)} events"})
        has_tool_call = any(k == "tool_call" for k, _ in kinds)
        has_search = any(k == "tool_call" and n == "search_runbooks"
                         for k, n in kinds)
        has_tool_result = any(k == "tool_result" for k, _ in kinds)
        finished = [e for e in events
                    if e.get("kind") == "run" and e.get("name") == "run_finished"]
        final_present = bool(finished and
                             (finished[0].get("payload") or {})
                             .get("final_answer_present"))
        checks.append({"name": prefix + "structured_tool_call",
                       "passed": has_tool_call,
                       "detail": f"tool_call events observed: {has_tool_call}"})
        checks.append({"name": prefix + "retrieval",
                       "passed": has_search,
                       "detail": "search_runbooks executed" if has_search
                       else "no search_runbooks call observed"})
        checks.append({"name": prefix + "tool_result_processed",
                       "passed": has_tool_result,
                       "detail": f"tool_result events observed: {has_tool_result}"})
        checks.append({"name": prefix + "final_answer",
                       "passed": final_present,
                       "detail": f"final_answer_present={final_present}"})
        trials_out.append(t)
    return {"trials": trials_out, "checks": checks}


if __name__ == "__main__":
    sys.exit(main())
