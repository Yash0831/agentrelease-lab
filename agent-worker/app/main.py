"""FastAPI service: job submission for evaluation matrices + health.

Heavy lifting lives in worker.py (Redis consumer) and agent.py (agent loop).
POST /jobs/{id}/run-sync executes a job inline — used by the benchmark script
and the one-command demo when Redis is unavailable.
"""
from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import queue
from .config import settings
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


@app.post("/jobs/{job_id}/run-sync")
def run_sync(job_id: str, job: JobRequest, authorization: str | None = Header(None)):
    _check_auth(authorization)
    result = run_job(job.model_dump())
    return {"job_id": job_id, **result}
