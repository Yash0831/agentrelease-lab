"""Replay tests: recordings reproduce model decisions with no network calls."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.llm import LLMResponse
from app.replay import RecordingError, ReplayLLM, TurnRecorder


def _version():
    return {"name": "baseline", "fingerprint": "fp-abc123"}


def _recording_file(tmp_path, **over):
    rec = TurnRecorder(version=_version(), scenario_id="happy-path-vpn",
                       trial_index=0, batch_id="batch-test",
                       model_id="test-model-1")
    resp = LLMResponse(
        tool_calls=[{"id": "call_1", "name": "get_ticket",
                     "arguments": {"ticketKey": "ACME-101"}}],
        provider="live", model="test-model-1",
        input_tokens=10, output_tokens=5)
    rec.record_turn(0, resp, [{"tool": "get_ticket", "args": {"ticketKey": "ACME-101"},
                              "status": "OK"}])
    resp2 = LLMResponse(final_answer="Ticket is open.", provider="live",
                        model="test-model-1", input_tokens=20, output_tokens=8)
    rec.record_turn(1, resp2, [])
    rec.final_answer = "Ticket is open."
    path = tmp_path / "rec.json"
    rec.save(path)
    if over:
        data = json.loads(path.read_text())
        data.update(over)
        path.write_text(json.dumps(data))
    return path


def test_replay_returns_recorded_decisions_in_order(tmp_path):
    path = _recording_file(tmp_path)
    llm = ReplayLLM(path, version=_version(), scenario_id="happy-path-vpn",
                     trial_index=0)
    r0 = llm.complete(version_name="baseline", scenario_id="happy-path-vpn",
                      step=0, messages=[], chaos={})
    assert r0.tool_calls[0]["name"] == "get_ticket"
    assert r0.tool_calls[0]["id"] == "call_1"
    assert r0.provider == "replay"
    assert r0.model == "test-model-1"
    r1 = llm.complete(version_name="baseline", scenario_id="happy-path-vpn",
                      step=1, messages=[], chaos={})
    assert r1.final_answer == "Ticket is open."
    assert r1.tool_calls == []


def test_replay_makes_no_network_calls(tmp_path, monkeypatch):
    """Networking disabled: replay must not attempt any HTTP."""
    import app.llm as llm_mod
    path = _recording_file(tmp_path)
    llm = ReplayLLM(path)

    def boom(*a, **kw):
        raise AssertionError("network call attempted during replay")

    monkeypatch.setattr(llm_mod.httpx, "post", boom)
    monkeypatch.setattr(llm_mod.httpx, "get", boom)
    r = llm.complete(version_name="x", scenario_id="y", step=0,
                     messages=[], chaos={})
    assert r.tool_calls[0]["name"] == "get_ticket"


def test_replay_rejects_wrong_fingerprint(tmp_path):
    path = _recording_file(tmp_path)
    with pytest.raises(RecordingError, match="fingerprint"):
        ReplayLLM(path, version={"name": "baseline", "fingerprint": "different"})


def test_replay_rejects_wrong_scenario(tmp_path):
    path = _recording_file(tmp_path)
    with pytest.raises(RecordingError, match="scenario"):
        ReplayLLM(path, scenario_id="other-scenario")


def test_replay_rejects_truncated_recording(tmp_path):
    path = _recording_file(tmp_path)
    llm = ReplayLLM(path)
    with pytest.raises(RecordingError, match="truncated"):
        llm.complete(version_name="x", scenario_id="y", step=99,
                     messages=[], chaos={})


def test_replay_rejects_bad_schema(tmp_path):
    path = _recording_file(tmp_path, schema_version=999)
    with pytest.raises(RecordingError, match="incompatible.*schema"):
        ReplayLLM(path)


def test_replay_rejects_missing_file(tmp_path):
    with pytest.raises(RecordingError, match="not found"):
        ReplayLLM(tmp_path / "nope.json")


def test_recording_sanitizes_no_credentials(tmp_path):
    path = _recording_file(tmp_path)
    data = json.loads(path.read_text())
    blob = json.dumps(data).lower()
    assert "bearer" not in blob
    assert "api_key" not in blob
    assert "authorization" not in blob
