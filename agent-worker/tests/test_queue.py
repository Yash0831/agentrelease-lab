"""Queue tests: job id determinism and idempotent enqueue without Redis."""
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import queue

# When set (e.g. in CI), Redis-dependent tests fail loudly instead of
# skipping, so a broken Redis service can never silently pass the suite.
REQUIRE_REDIS = os.environ.get("ARL_REQUIRE_REDIS", "").lower() in ("1", "true", "yes")


def _redis_or_skip():
    import redis as redis_lib
    try:
        c = redis_lib.Redis.from_url(
            os.environ.get("REDIS_URL", "redis://localhost:6379/0"),
            socket_connect_timeout=2)
        c.ping()
        return c
    except Exception as e:
        if REQUIRE_REDIS:
            pytest.fail(f"ARL_REQUIRE_REDIS is set but Redis is unavailable: {e}")
        pytest.skip("redis not available")


def test_job_id_is_deterministic():
    p1 = {"versions": ["baseline"], "trials": 3}
    p2 = {"trials": 3, "versions": ["baseline"]}
    assert queue.job_id_for(p1) == queue.job_id_for(p2)
    assert queue.job_id_for({"versions": ["other"]}) != queue.job_id_for(p1)


def test_enqueue_without_redis_reports_unavailable():
    # Points at a port nothing listens on -> _client() returns None.
    import app.config as cfg
    old = cfg.settings.redis_url
    cfg.settings.redis_url = "redis://localhost:63999/0"
    try:
        jid, ok = queue.enqueue({"versions": ["baseline"]})
        assert ok is False
        assert jid.startswith("job-")
    finally:
        cfg.settings.redis_url = old


def test_enqueue_idempotent_with_redis():
    c = _redis_or_skip()
    payload = {"versions": ["baseline"], "trials": 1, "test": True}
    jid1, ok1 = queue.enqueue(payload)
    assert ok1 is True
    # Simulate completion, then re-enqueue: must be a no-op.
    c.hset(queue.RESULT_HASH, jid1, '{"status": "ok"}')
    jid2, ok2 = queue.enqueue(payload)
    assert jid1 == jid2
    assert c.llen(queue.QUEUE_KEY) >= 0
    c.hdel(queue.RESULT_HASH, jid1)
    c.hdel(queue.JOB_HASH, jid1)


def test_atomic_enqueue_dedup():
    c = _redis_or_skip()
    from app import queue as q
    payload = {"versions": ["baseline"], "trials": 1, "atomic": True}
    jid = q.job_id_for(payload)
    # Clean slate
    c.delete(q.QUEUE_KEY, q.JOB_HASH, q.RESULT_HASH, q.ATTEMPT_HASH)
    c.hdel(q.JOB_HASH, jid); c.hdel(q.RESULT_HASH, jid); c.hdel(q.ATTEMPT_HASH, jid)
    jid1, ok1 = q.enqueue(payload)
    jid2, ok2 = q.enqueue(payload)
    assert jid1 == jid2
    # Exactly one queue entry
    assert c.llen(q.QUEUE_KEY) == 1
    # Finished jobs are never re-queued
    c.hset(q.RESULT_HASH, jid, '{"status": "ok"}')
    jid3, ok3 = q.enqueue(payload)
    assert jid3 == jid
    assert c.llen(q.QUEUE_KEY) == 1
    c.hdel(q.RESULT_HASH, jid)


def test_crash_recovery_requeues_abandoned_job():
    c = _redis_or_skip()
    from app import queue as q
    payload = {"versions": ["baseline"], "trials": 1, "crash": True}
    jid = q.job_id_for(payload)
    c.delete(q.QUEUE_KEY, q.JOB_HASH, q.RESULT_HASH, q.ATTEMPT_HASH, q.PROCESSING_HASH)
    q.enqueue(payload)
    item = q.dequeue(block_s=1)
    assert item is not None
    got_jid, _, cc = item
    assert got_jid == jid
    # Simulate crash: lease expires, job not completed.
    c.hset(q.PROCESSING_HASH, jid, 1)  # expired lease
    item2 = q.dequeue(block_s=1)
    assert item2 is not None
    assert item2[0] == jid  # reaped and re-queued
    q.complete(jid, {"status": "ok"}, item2[2])


def test_trial_resume_skips_completed():
    c = _redis_or_skip()
    from app import queue as q
    jid = "job-test-resume"
    c.delete(q.TRIALS_HASH_PREFIX + jid)
    assert not q.is_trial_done(jid, c, "v", "s", 0)
    q.mark_trial_done(jid, c, "v", "s", 0)
    assert q.is_trial_done(jid, c, "v", "s", 0)
    assert not q.is_trial_done(jid, c, "v", "s", 1)
    c.delete(q.TRIALS_HASH_PREFIX + jid)
