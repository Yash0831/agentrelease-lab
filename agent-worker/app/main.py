"""FastAPI service: job submission for evaluation matrices + health.

Heavy lifting lives in worker.py (Redis consumer) and agent.py (agent loop).
POST /jobs/run-sync (or /jobs/{id}/run-sync) executes a job inline — used by
the benchmark script and the one-command demo when Redis is unavailable.
"""
from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import queue
from .config import settings
from .explain import explain_incident
from .worker import run_job

app = FastAPI(title="AgentRelease Lab — agent worker")


class JobRequest(BaseModel):
    versions: list[str]          # agent version names
    dataset_id: str = "it-service-desk-v1"
    scenarios: list[str] | None = None
    trials: int = 3
    mode: str = "fixture"        # fixture | live | replay
    chaos_overrides: dict = {}
    approve_pending: bool = False  # auto-approve PENDING approvals (simulated reviewer)
    judge: bool = False
    batch_id: str | None = None  # evaluation batch; generated if absent
    recording_path: str | None = None  # replay mode: path to the recording


def _check_auth(authorization: str | None):
    # Demo-grade auth for the worker's own API.
    if settings.worker_api_key and authorization != f"Bearer {settings.worker_api_key}":
        raise HTTPException(401, "unauthorized")


@app.get("/health")
def health():
    return {"status": "ok", "service": "arl-worker"}


@app.post("/jobs")
def submit_job(job: JobRequest, authorization: str | None = Header(None)):
    _check_auth(authorization)
    payload = job.model_dump()
    jid, redis_ok = queue.enqueue(payload)
    return {"job_id": jid, "redis": redis_ok,
            "note": None if redis_ok else "Redis unavailable; use run-sync to execute inline"}


@app.get("/jobs/{job_id}")
def job_status(job_id: str, authorization: str | None = Header(None)):
    _check_auth(authorization)
    result = queue.get_result(job_id)
    if result is None:
        return {"job_id": job_id, "status": "pending_or_unknown"}
    return {"job_id": job_id, **result}


@app.post("/jobs/run-sync")
def run_sync_no_id(job: JobRequest, authorization: str | None = Header(None)):
    """Same as /jobs/{job_id}/run-sync with a server-generated job id."""
    _check_auth(authorization)
    import uuid
    job_id = uuid.uuid4().hex[:12]
    result = run_job(job.model_dump())
    return {"job_id": job_id, **result}


@app.post("/jobs/{job_id}/run-sync")
def run_sync(job_id: str, job: JobRequest, authorization: str | None = Header(None)):
    _check_auth(authorization)
    result = run_job(job.model_dump())
    return {"job_id": job_id, **result}


class ExplainRequest(BaseModel):
    eval_run_id: str
    candidate: str
    baseline: str = "baseline"
    mode: str = "fixture"


@app.post("/incidents/explain")
def explain(req: ExplainRequest, authorization: str | None = Header(None)):
    """Evidence-based incident explanation (requirement 7). Facts are
    deterministic; hypotheses are labeled; no causal certainty is claimed."""
    _check_auth(authorization)
    from .platform_client import PlatformClient
    platform = PlatformClient()
    return explain_incident(platform, req.eval_run_id, req.candidate,
                            req.baseline, mode=req.mode)
