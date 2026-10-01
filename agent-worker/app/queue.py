"""Redis job queue with atomic deduplication, crash recovery, and resumable trials.

Jobs are eval-matrix runs. Each job has a stable id derived from its payload.

Atomicity: enqueue uses a Lua script so the "already finished -> don't queue"
check and the queue push happen atomically.

Crash recovery: dequeued jobs move to a processing hash with a lease timestamp.
If a worker crashes, the lease expires and a reaper (or the next dequeue)
returns the job to the pending queue. Heartbeats extend the lease.

Resumable trials: each completed trial is recorded in a per-job hash. A
retried job skips trials that already completed, so retries never duplicate
side effects.
"""
from __future__ import annotations

import hashlib
import json
import time
import uuid

import redis

from .config import settings

QUEUE_KEY = "arl:jobs:pending"
JOB_HASH = "arl:jobs:meta"          # job_id -> job json
RESULT_HASH = "arl:jobs:results"    # job_id -> result json
ATTEMPT_HASH = "arl:jobs:attempts"  # job_id -> attempt count
PROCESSING_HASH = "arl:jobs:processing"  # job_id -> lease timestamp (ms)
TRIALS_HASH_PREFIX = "arl:jobs:trials:"  # job_id -> {trial_key: 1}

MAX_ATTEMPTS = 3
BACKOFF_BASE_S = 2
LEASE_MS = 120_000  # worker must heartbeat every 2 minutes


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


# Atomic enqueue: if a result exists, do nothing; otherwise store the job
# and push it exactly once.
_ENQUEUE_LUA = """
if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then
  return 0
end
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
  redis.call('HSET', KEYS[3], ARGV[1], 0)
  redis.call('RPUSH', KEYS[4], ARGV[1])
  return 1
end
return 0
"""


def enqueue(payload: dict) -> tuple[str, bool]:
    """Returns (job_id, redis_available). Atomic and idempotent."""
    c = _client()
    if c is None:
        return job_id_for(payload), False
    jid = job_id_for(payload)
    c.eval(_ENQUEUE_LUA, 4, JOB_HASH, RESULT_HASH, ATTEMPT_HASH, QUEUE_KEY,
           jid, json.dumps(payload))
    return jid, True


def _reap_expired(c: "redis.Redis") -> None:
    """Return crashed workers' jobs to the pending queue."""
    now_ms = int(time.time() * 1000)
    for jid, lease in c.hgetall(PROCESSING_HASH).items():
        jid = jid.decode()
        if int(lease.decode()) < now_ms:
            # Lease expired: worker crashed. Re-queue if not finished.
            if not c.hexists(RESULT_HASH, jid):
                c.rpush(QUEUE_KEY, jid)
            c.hdel(PROCESSING_HASH, jid)


def dequeue(block_s: int = 5) -> tuple[str, dict, "redis.Redis"] | None:
    c = _client()
    if c is None:
        return None
    _reap_expired(c)
    item = c.blpop(QUEUE_KEY, timeout=block_s)
    if not item:
        return None
    jid = item[1].decode()
    # If already finished (race with reaper), skip.
    if c.hexists(RESULT_HASH, jid):
        c.hdel(PROCESSING_HASH, jid)
        return None
    payload = json.loads(c.hget(JOB_HASH, jid))
    # Claim with a lease.
    c.hset(PROCESSING_HASH, jid, int(time.time() * 1000) + LEASE_MS)
    return jid, payload, c


def heartbeat(jid: str, c: "redis.Redis") -> None:
    """Extend the processing lease. Call periodically during long jobs."""
    c.hset(PROCESSING_HASH, jid, int(time.time() * 1000) + LEASE_MS)


def record_attempt(jid: str, c: "redis.Redis") -> int:
    return int(c.hincrby(ATTEMPT_HASH, jid, 1))


def complete(jid: str, result: dict, c: "redis.Redis") -> None:
    c.hset(RESULT_HASH, jid, json.dumps(result))
    c.hdel(PROCESSING_HASH, jid)
    # Trial state is no longer needed once the job completes.
    c.delete(TRIALS_HASH_PREFIX + jid)


def requeue_or_dead(jid: str, c: "redis.Redis", attempts: int) -> str:
    if attempts >= MAX_ATTEMPTS:
        complete(jid, {"status": "dead", "error": "max attempts exceeded"}, c)
        return "dead"
    c.hdel(PROCESSING_HASH, jid)
    time.sleep(min(BACKOFF_BASE_S ** attempts, 30))
    c.rpush(QUEUE_KEY, jid)
    return "requeued"


def get_result(jid: str) -> dict | None:
    c = _client()
    if c is None:
        return None
    raw = c.hget(RESULT_HASH, jid)
    return json.loads(raw) if raw else None


def trial_key(version: str, scenario: str, trial: int) -> str:
    return f"{version}:{scenario}:{trial}"


def mark_trial_done(jid: str, c: "redis.Redis", version: str,
                    scenario: str, trial: int) -> None:
    c.hset(TRIALS_HASH_PREFIX + jid, trial_key(version, scenario, trial), 1)


def is_trial_done(jid: str, c: "redis.Redis", version: str,
                  scenario: str, trial: int) -> bool:
    return bool(c.hexists(TRIALS_HASH_PREFIX + jid,
                          trial_key(version, scenario, trial)))
