"""Redis job queue with bounded retries and idempotent processing.

Jobs are eval-matrix runs. Each job has a stable id; results are recorded once
per (job_id, attempt) and completion is idempotent: re-delivery of a finished
job returns the stored result instead of re-running.
"""
from __future__ import annotations

import hashlib
import json
import time
import uuid

import redis

from .config import settings

QUEUE_KEY = "arl:jobs:pending"
JOB_HASH = "arl:jobs:meta"       # job_id -> job json
RESULT_HASH = "arl:jobs:results"  # job_id -> result json
ATTEMPT_HASH = "arl:jobs:attempts"

MAX_ATTEMPTS = 3
BACKOFF_BASE_S = 2


def _client() -> redis.Redis | None:
    try:
        c = redis.Redis.from_url(settings.redis_url, socket_connect_timeout=2)
        c.ping()
        return c
    except Exception:
        return None


def job_id_for(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True)
    return "job-" + hashlib.sha256(canonical.encode()).hexdigest()[:16]


def enqueue(payload: dict) -> tuple[str, bool]:
    """Returns (job_id, redis_available). Idempotent: same payload -> same id,
    and a finished job is never re-queued."""
    c = _client()
    if c is None:
        return job_id_for(payload), False
    jid = job_id_for(payload)
    if c.hexists(RESULT_HASH, jid):
        return jid, True  # already finished: idempotent no-op
    if not c.hexists(JOB_HASH, jid):
        c.hset(JOB_HASH, jid, json.dumps(payload))
        c.hset(ATTEMPT_HASH, jid, 0)
        c.rpush(QUEUE_KEY, jid)
    return jid, True


def dequeue(block_s: int = 5) -> tuple[str, dict, "redis.Redis"] | None:
    c = _client()
    if c is None:
        return None
    item = c.blpop(QUEUE_KEY, timeout=block_s)
    if not item:
        return None
    jid = item[1].decode()
    payload = json.loads(c.hget(JOB_HASH, jid))
    return jid, payload, c


def record_attempt(jid: str, c: "redis.Redis") -> int:
    return int(c.hincrby(ATTEMPT_HASH, jid, 1))


def complete(jid: str, result: dict, c: "redis.Redis") -> None:
    c.hset(RESULT_HASH, jid, json.dumps(result))


def requeue_or_dead(jid: str, c: "redis.Redis", attempts: int) -> str:
    if attempts >= MAX_ATTEMPTS:
        complete(jid, {"status": "dead", "error": "max attempts exceeded"}, c)
        return "dead"
    time.sleep(min(BACKOFF_BASE_S ** attempts, 30))
    c.rpush(QUEUE_KEY, jid)
    return "requeued"


def get_result(jid: str) -> dict | None:
    c = _client()
    if c is None:
        return None
    raw = c.hget(RESULT_HASH, jid)
    return json.loads(raw) if raw else None
