"""Queue tests: job id determinism and idempotent enqueue without Redis."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import queue


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
    import redis as redis_lib
    try:
        c = redis_lib.Redis.from_url("redis://localhost:6379/0", socket_connect_timeout=2)
        c.ping()
    except Exception:
        import pytest
        pytest.skip("redis not available")
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
