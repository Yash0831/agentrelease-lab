"""Live-path unit tests: timeouts, rate limits, bounded retries, concurrency
bound, observability counters, log redaction, and the live-smoke harness
gating. No network and no credentials are required: the HTTP layer is
monkeypatched."""
import json
import sys
import threading
import time
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent
                       / "benchmarks"))

import app.llm as llm_mod
from app.config import settings
from app.llm import (LiveLLM, RateLimitError, call_with_retries, telemetry,
                     _live_semaphore)
from app.metrics import compute_metrics, estimate_cost_usd, load_price_table
from app.replay import _sanitize_text
from run_live_smoke import blocked, clamp_concurrency, main as smoke_main


@pytest.fixture(autouse=True)
def _clean_telemetry():
    telemetry.reset()
    yield
    telemetry.reset()


def _ok_response(tool_calls=None, content="done", in_tok=0, out_tok=0):
    return httpx.Response(
        200,
        json={"choices": [{"message": {"content": content,
                                       "tool_calls": tool_calls or []}}],
              "usage": {"prompt_tokens": in_tok,
                        "completion_tokens": out_tok}},
        request=httpx.Request("POST", "http://provider.test/chat/completions"))


def _live(model="unpriced-model-x"):
    return LiveLLM(base_url="http://provider.test", api_key="sk-test-key",
                   model=model, timeout_s=5)


KW = dict(version_name="v", scenario_id="happy-path-vpn", step=0,
          messages=[{"role": "user", "content": "hi"}], chaos={})


# --- harness gating (no run without explicit live mode + full env) ---

def _argv(*a):
    return ["run_live_smoke.py", *a]


def test_harness_requires_explicit_live_mode(monkeypatch, capsys, tmp_path):
    monkeypatch.setattr(sys, "argv", _argv("--mode", "fixture",
                                           "--out", str(tmp_path)))
    rc = smoke_main()
    out = capsys.readouterr().out
    assert rc == 2
    assert "BLOCKED" in out
    assert list(tmp_path.glob("live-smoke-*.json")) == []


def test_harness_blocked_when_env_missing(monkeypatch, capsys, tmp_path):
    for v in ("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL"):
        monkeypatch.delenv(v, raising=False)
    monkeypatch.setenv("LLM_MODEL", "some-model")  # partial config
    monkeypatch.setattr(sys, "argv", _argv("--mode", "live",
                                           "--out", str(tmp_path)))
    rc = smoke_main()
    out = capsys.readouterr().out
    assert rc == 2
    payload = json.loads(out)
    assert payload["status"] == "BLOCKED"
    assert "LLM_BASE_URL" in payload["missing_env"]
    assert "LLM_API_KEY" in payload["missing_env"]
    # The missing-vars report must name variables, never values.
    assert "some-model" not in out
    assert list(tmp_path.glob("live-smoke-*.json")) == []


def test_blocked_helper_names_missing_vars(capsys):
    assert blocked("test", missing=["LLM_API_KEY"]) == 2
    payload = json.loads(capsys.readouterr().out)
    assert payload == {"status": "BLOCKED", "mode": "live", "reason": "test",
                       "missing_env": ["LLM_API_KEY"]}


def test_concurrency_hard_cap():
    assert clamp_concurrency(1) == (1, None)
    assert clamp_concurrency(4) == (4, None)
    n, note = clamp_concurrency(10)
    assert n == 4 and "hard limit" in note
    assert clamp_concurrency(0)[0] == 1


# --- live provider: timeout / rate-limit handling and bounded retries ---

def test_live_timeout_raises_and_counts_failure(monkeypatch):
    def boom(*a, **k):
        raise httpx.TimeoutException("connect timed out")
    monkeypatch.setattr(llm_mod.httpx, "post", boom)
    with pytest.raises(TimeoutError):
        _live().complete(**KW)
    snap = telemetry.snapshot()
    assert snap["provider_failures_timeout"] == 1
    assert snap["provider_calls"] == 0


def test_live_429_raises_rate_limit_error_and_counts_failure(monkeypatch):
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: httpx.Response(429, text="slow down"))
    with pytest.raises(RateLimitError):
        _live().complete(**KW)
    snap = telemetry.snapshot()
    assert snap["provider_failures_429"] == 1
    assert snap["provider_calls"] == 0


def test_live_429_error_body_is_redacted(monkeypatch):
    monkeypatch.setattr(
        llm_mod.httpx, "post",
        lambda *a, **k: httpx.Response(
            429, text="denied: api_key=sk-live-abc123 leaked upstream"))
    with pytest.raises(RateLimitError) as ei:
        _live().complete(**KW)
    assert "sk-live-abc123" not in str(ei.value)
    assert "api_key=<redacted>" in str(ei.value)


def test_live_retries_are_bounded(monkeypatch):
    calls = []
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: calls.append(1) or
                        httpx.Response(429, text="slow"))
    monkeypatch.setattr(llm_mod.time, "sleep", lambda s: None)
    with pytest.raises(RateLimitError):
        call_with_retries(_live(), **KW, max_retries=3)
    assert len(calls) == 4  # initial attempt + 3 retries, then give up
    assert telemetry.snapshot()["llm_retries"] == 3


def test_live_retry_then_success(monkeypatch):
    chaos = {"llm_rate_limit": {"fail_times": 2}}
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: _ok_response())
    monkeypatch.setattr(llm_mod.time, "sleep", lambda s: None)
    kw = dict(KW, chaos=chaos)
    resp, retries = call_with_retries(_live(), **kw, max_retries=3)
    assert retries == 2
    assert resp.provider == "live"
    assert telemetry.snapshot()["llm_retries"] == 2


# --- cost accounting: unknown pricing is null, never zero ---

def test_unknown_model_cost_is_none_not_zero():
    assert estimate_cost_usd({"models": {}}, "nope", 1000, 500) is None
    table = load_price_table()
    got = estimate_cost_usd(table, "unpriced-model-x", 1000, 500)
    assert got is None
    known = estimate_cost_usd(table, "example-live-a", 1000, 500)
    assert known == pytest.approx(1000 / 1e6 * 3.0 + 500 / 1e6 * 12.0)


def test_compute_metrics_unknown_model_cost_null():
    result = {
        "version": {"model_id": "unpriced-model-x"},
        "events": [], "retrieved_hits": [], "final_answer": "I found nothing.",
        "citations": [], "failure_reason": None, "latency_ms": 10.0,
        "tool_call_count": 0, "llm_retries": 0,
        "input_tokens": 1000, "output_tokens": 500, "mode": "live",
    }
    m = compute_metrics(result=result,
                        scenario={"id": "empty-retrieval"},
                        platform=None, price_table=load_price_table())
    assert m["estimated_cost_usd"] is None
    assert m["pricing_status"] == "unknown"


def test_compute_metrics_known_model_cost_reported():
    result = {
        "version": {"model_id": "example-live-a"},
        "events": [], "retrieved_hits": [], "final_answer": "I found nothing.",
        "citations": [], "failure_reason": None, "latency_ms": 10.0,
        "tool_call_count": 0, "llm_retries": 0,
        "input_tokens": 1000, "output_tokens": 500, "mode": "live",
    }
    m = compute_metrics(result=result,
                        scenario={"id": "empty-retrieval"},
                        platform=None, price_table=load_price_table())
    assert m["pricing_status"] == "known"
    assert m["estimated_cost_usd"] == pytest.approx(0.009)


def test_live_success_records_tokens_and_cost_telemetry(monkeypatch):
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: _ok_response(in_tok=100, out_tok=50))
    resp = _live().complete(**KW)  # model not in price table
    assert resp.input_tokens == 100 and resp.output_tokens == 50
    snap = telemetry.snapshot()
    assert snap["provider_calls"] == 1
    assert snap["input_tokens"] == 100 and snap["output_tokens"] == 50
    assert snap["unknown_cost_calls"] == 1
    assert snap["estimated_cost_usd"] is None  # unknown, not 0.0


def test_live_success_records_known_cost(monkeypatch):
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: _ok_response(in_tok=100, out_tok=50))
    _live(model="example-live-a").complete(**KW)
    snap = telemetry.snapshot()
    assert snap["unknown_cost_calls"] == 0
    assert snap["estimated_cost_usd"] == pytest.approx(0.0009)


# --- concurrency bound ---

def test_live_concurrency_bound_enforced(monkeypatch):
    monkeypatch.setattr(settings, "llm_max_concurrency", 2)
    in_flight = 0
    peak = 0
    lock = threading.Lock()

    def slow_post(*a, **k):
        nonlocal in_flight, peak
        with lock:
            in_flight += 1
            peak = max(peak, in_flight)
        time.sleep(0.05)
        with lock:
            in_flight -= 1
        return _ok_response()

    monkeypatch.setattr(llm_mod.httpx, "post", slow_post)
    llm = _live()
    threads = [threading.Thread(target=lambda: llm.complete(**KW))
               for _ in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert peak <= 2
    assert peak >= 2  # the bound was actually exercised, not vacuous
    monkeypatch.setattr(settings, "llm_max_concurrency", 2)  # reset size
    _live_semaphore()  # rebuild for the restored size


# --- observability: no credentials in logs ---

def test_api_key_never_logged(monkeypatch, caplog):
    def boom(*a, **k):
        raise httpx.TimeoutException("connect timed out")
    monkeypatch.setattr(llm_mod.httpx, "post", boom)
    llm = LiveLLM(base_url="http://provider.test",
                  api_key="sk-test-secret-xyz", model="m", timeout_s=5)
    with caplog.at_level("WARNING", logger="app.llm"):
        with pytest.raises(TimeoutError):
            llm.complete(**KW)
    assert "sk-test-secret-xyz" not in caplog.text


def test_success_log_line_has_no_credential(monkeypatch, caplog):
    monkeypatch.setattr(llm_mod.httpx, "post",
                        lambda *a, **k: _ok_response(in_tok=10, out_tok=5))
    llm = LiveLLM(base_url="http://provider.test",
                  api_key="sk-test-secret-xyz", model="m", timeout_s=5)
    with caplog.at_level("INFO", logger="app.llm"):
        llm.complete(**KW)
    assert "sk-test-secret-xyz" not in caplog.text
    assert "llm_call ok" in caplog.text


def test_credential_rotation_replaces_authorization_header(monkeypatch):
    seen = []

    def capture(*a, **k):
        seen.append(k["headers"])
        return _ok_response()
    monkeypatch.setattr(llm_mod.httpx, "post", capture)
    LiveLLM(base_url="http://p", api_key="old-key", model="m").complete(**KW)
    LiveLLM(base_url="http://p", api_key="new-key", model="m").complete(**KW)
    assert seen[0]["Authorization"] == "Bearer old-key"
    assert seen[1]["Authorization"] == "Bearer new-key"


# --- redaction of credential fragments in recordings/logs ---

@pytest.mark.parametrize("raw", [
    "tool failed: llm_api_key=sk-live-abc123, request denied",
    "upstream said api-key: sk-live-abc123 for tenant acme",
    "Authorization: Bearer tok_xyz_999",
    "db password=hunter2 rejected",
    "token=abc123 expired",
])
def test_sanitize_text_redacts_credential_fragments(raw):
    out = _sanitize_text(raw)
    assert "sk-live-abc123" not in out
    assert "tok_xyz_999" not in out
    assert "hunter2" not in out
    assert "abc123" not in out
    assert "<redacted>" in out


def test_sanitize_text_keeps_decision_relevant_content():
    out = _sanitize_text("TICKET_NOT_FOUND for ACME-101 after 2 retries")
    assert out == "TICKET_NOT_FOUND for ACME-101 after 2 retries"


# --- worker /health reports redis + queue depth ---

def test_health_reports_redis_and_queue_depth(monkeypatch):
    from fastapi.testclient import TestClient
    from app.main import app
    import app.queue as qmod

    class FakeRedis:
        def llen(self, key):
            return 3
    monkeypatch.setattr(qmod, "_client", lambda: FakeRedis())
    r = TestClient(app).get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["redis"] == "up"
    assert body["queue_depth"] == 3

    monkeypatch.setattr(qmod, "_client", lambda: None)
    body = TestClient(app).get("/health").json()
    assert body["redis"] == "down"
    assert body["queue_depth"] is None
