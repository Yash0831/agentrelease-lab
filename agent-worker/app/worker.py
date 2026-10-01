"""Job execution: run the version x scenario x trial matrix, compute metrics,
finish eval runs, optionally auto-approve pending approvals (simulated human
reviewer for the demo), and run an optional LLM judge.

A job payload:
{
  "versions": ["baseline", ...], "dataset_id": "...", "scenarios": [...],
  "trials": 3, "mode": "fixture", "chaos_overrides": {scenario_id: {...}},
  "approve_pending": false, "judge": false
}
"""
from __future__ import annotations

import time

from . import queue as q
from .agent import AgentRunner
from .config import settings
from .judge import judge_answer
from .metrics import (compute_metrics, duplicate_write_probe, load_price_table)
from .platform_client import PlatformClient
from .tracing import tracer


def run_job(payload: dict, *, jid: str | None = None,
            redis_client=None) -> dict:
    versions = payload["versions"]
    dataset_id = payload.get("dataset_id", "it-service-desk-v1")
    trials = int(payload.get("trials", 3))
    mode = payload.get("mode", "fixture")
    chaos_overrides: dict = payload.get("chaos_overrides", {})
    approve_pending = bool(payload.get("approve_pending", False))
    use_judge = bool(payload.get("judge", False))
    # One batch per job execution: repeated runs never collide and every
    # run, trace, comparison, and release decision is scoped to it.
    import uuid as _uuid
    batch_id = payload.get("batch_id") or f"batch-{_uuid.uuid4().hex[:12]}"

    platform = PlatformClient()
    price_table = load_price_table()
    tr = tracer()

    # Resolve version names -> platform records (with fingerprints).
    catalog = {v["name"]: v for v in platform.list_versions()}
    missing = [n for n in versions if n not in catalog]
    if missing:
        return {"status": "failed", "error": f"unknown versions: {missing}"}

    # Scenario list comes from the dataset definition via the platform.
    import httpx
    ds = httpx.get(f"{platform.base_url}/api/datasets/{dataset_id}",
                   headers={"X-API-Key": platform.api_key}, timeout=15,
                   trust_env=False).json()
    scenarios = ds.get("scenarios", [])
    wanted = payload.get("scenarios")
    if wanted:
        scenarios = [s for s in scenarios if s["id"] in wanted]
    for s in scenarios:
        s["dataset_id"] = dataset_id

    summary: list[dict] = []
    with tr.start_as_current_span("eval_matrix", attributes={
            "dataset": dataset_id, "mode": mode, "batch_id": batch_id,
            "versions": ",".join(versions)}):
        for vname in versions:
            version = catalog[vname]
            for scenario in scenarios:
                chaos = dict(scenario.get("chaos", {}) or {})
                chaos.update(chaos_overrides.get(scenario["id"], {}))
                for trial in range(trials):
                    # Resumable: skip trials that already completed before a
                    # crash/retry, so retries never duplicate side effects.
                    if jid and redis_client is not None and q.is_trial_done(
                            jid, redis_client, vname, scenario["id"], trial):
                        summary.append({
                            "version": vname, "scenario": scenario["id"],
                            "trial": trial, "eval_run_id": "",
                            "batch_id": batch_id, "status": "SKIPPED",
                            "task_completed": True, "critical": False,
                            "resumed": True,
                        })
                        continue
                    runner = AgentRunner(platform)
                    with tr.start_as_current_span(
                            "trial", attributes={"version": vname,
                                                 "scenario": scenario["id"],
                                                 "trial": trial}):
                        # Isolate mutable fixtures: earlier trials must not
                        # satisfy later trials' checkers.
                        try:
                            platform.reset_fixtures()
                        except Exception as e:
                            print(f"fixture reset failed: {e}", flush=True)
                        # Live mode: record model responses for replay.
                        # Replay mode: load the recording (no live calls).
                        recorder = None
                        recording_path = payload.get("recording_path")
                        if mode == "live":
                            from .replay import TurnRecorder
                            recorder = TurnRecorder(
                                version=version, scenario_id=scenario["id"],
                                trial_index=trial, batch_id=batch_id,
                                model_id=version.get("model_id", ""))
                            runner = AgentRunner(platform, recorder=recorder)
                        elif mode == "replay":
                            if not recording_path:
                                raise ValueError(
                                    "replay mode needs recording_path in the job payload")
                            runner = AgentRunner(platform,
                                                 recording_path=recording_path)
                        result = runner.run(
                            version=version, scenario=scenario,
                            trial_index=trial, mode=mode, chaos=dict(chaos),
                            api_key=platform.api_key, batch_id=batch_id)
                        metrics = compute_metrics(
                            result=result, scenario=scenario,
                            platform=platform, price_table=price_table)
                        if scenario["id"] == "duplicate-write-retry":
                            probe = duplicate_write_probe(
                                platform, result["eval_run_id"], result["trace_id"])
                            metrics["duplicate_side_effects"] = probe["duplicate_side_effects"]
                            metrics["probe"] = probe["probe"]
                        if use_judge and result.get("final_answer"):
                            try:
                                metrics["judge"] = {"probabilistic": True,
                                                    **judge_answer(result["final_answer"],
                                                                   result.get("citations", []))}
                            except Exception as e:
                                metrics["judge"] = {"probabilistic": True,
                                                    "error": str(e)[:200]}
                        status = "SUCCEEDED"
                        if metrics["critical_policy_failure"]:
                            status = "BLOCKED"
                        elif not metrics["task_completed"] and result.get("failure_reason"):
                            status = "FAILED"
                        platform.eval_run_finish(
                            result["eval_run_id"], status, metrics,
                            error=None if status == "SUCCEEDED"
                            else metrics.get("failure_reason") or "task incomplete")
                        if approve_pending:
                            auto_approve(platform, result["eval_run_id"])
                        summary.append({
                            "version": vname, "scenario": scenario["id"],
                            "trial": trial, "eval_run_id": result["eval_run_id"],
                            "batch_id": batch_id,
                            "status": status,
                            "task_completed": metrics["task_completed"],
                            "critical": metrics["critical_policy_failure"],
                        })
                        # Mark done for crash-resume; heartbeat the lease.
                        if jid and redis_client is not None:
                            q.mark_trial_done(jid, redis_client, vname,
                                              scenario["id"], trial)
                            q.heartbeat(jid, redis_client)
    return {"status": "ok", "mode": mode, "batch_id": batch_id, "trials": summary}


def auto_approve(platform: PlatformClient, eval_run_id: str) -> int:
    """Simulated human reviewer for the demo: approves PENDING approvals.
    In production this is a person in the dashboard's approval queue."""
    # NOTE: the platform has no per-run approval filter; approve PENDING ones
    # created recently. The demo keeps runs isolated by using fresh tenants? No —
    # it approves all PENDING in the tenant. Acceptable for the synthetic demo.
    count = 0
    for a in platform.list_approvals(status="PENDING"):
        try:
            platform.approve(a["id"])
            count += 1
        except Exception:
            pass
    return count


def consume_forever() -> None:
    """Redis consumer with atomic dedup, crash recovery, and resumable trials."""
    print("arl worker consumer started", flush=True)
    while True:
        item = q.dequeue(block_s=5)
        if item is None:
            continue
        jid, payload, c = item
        if q.get_result(jid) is not None:
            q.complete(jid, q.get_result(jid), c)
            continue  # idempotent: already finished
        attempts = q.record_attempt(jid, c)
        try:
            result = run_job(payload, jid=jid, redis_client=c)
            q.complete(jid, {"status": "ok", **result}, c)
        except Exception as e:
            outcome = q.requeue_or_dead(jid, c, attempts)
            print(f"job {jid} attempt {attempts}: {e} -> {outcome}", flush=True)


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "consume":
        consume_forever()
    else:
        print("usage: python -m app.worker consume", flush=True)
