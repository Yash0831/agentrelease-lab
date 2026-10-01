"""AgentRunner replay integration: replay mode through the full run loop.

Covers:
- Fix 1: replay branch initializes scenario/version before provider select
  (UnboundLocalError regression).
- Fix 2: final-answer-only turns are recorded; multi-turn recordings replay
  through AgentRunner with networking disabled.
- Fix 3: tool calls + text do not terminate the loop early.
- Fix 4: replay plays back recorded tool results; no platform calls.
"""
import json
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.agent import AgentRunner
from app.llm import LLMResponse
from app.replay import RecordingError, ReplayToolExecutor, TurnRecorder


def _version():
    return {"id": "v-1", "name": "baseline", "fingerprint": "fp-abc123",
            "model_id": "test-model", "prompt": "test prompt"}


def _scenario():
    return {"id": "happy-path-vpn", "task": "Check the VPN ticket",
            "dataset_id": "it-service-desk-v1"}


def _platform():
    p = MagicMock()
    p.eval_run_create.return_value = {"id": "run-1", "batchId": "batch-1"}
    p.trace_events.return_value = {}
    return p


def _write_recording(tmp_path, turns):
    """Write a recording JSON directly (bypasses TurnRecorder)."""
    data = {
        "schema_version": 1,
        "mode": "live",
        "model_id": "test-model",
        "version": {"name": "baseline", "fingerprint": "fp-abc123"},
        "scenario_id": "happy-path-vpn",
        "trial_index": 0,
        "batch_id": "batch-1",
        "recorded_at": "2026-09-30T00:00:00+00:00",
        "config": {"temperature": 0},
        "turns": turns,
        "retrieved_docs": [],
        "final_answer": "Ticket is open.",
    }
    path = tmp_path / "rec.json"
    path.write_text(json.dumps(data))
    return path


def _turn(step, tool_calls=None, content=None, executions=None):
    return {
        "step": step,
        "response": {
            "tool_calls": tool_calls or [],
            "content": content,
            "input_tokens": 10, "output_tokens": 5,
            "provider": "live", "model": "test-model",
        },
        "tool_executions": executions or [],
    }


# --- Fix 1: replay initialization -------------------------------------------

def test_replay_mode_initializes_scenario_before_provider(tmp_path):
    """UnboundLocalError regression: scenario_id must be assigned before
    ReplayLLM is constructed."""
    path = _write_recording(tmp_path, [
        _turn(0, content="Ticket is open."),
    ])
    runner = AgentRunner(_platform(), recording_path=str(path))
    result = runner.run(version=_version(), scenario=_scenario(),
                        trial_index=0, mode="replay", chaos={})
    assert result["final_answer"] == "Ticket is open."
    assert result["failure_reason"] is None


def test_replay_mode_missing_recording_path():
    runner = AgentRunner(_platform())
    with pytest.raises(ValueError, match="recording"):
        runner.run(version=_version(), scenario=_scenario(),
                   trial_index=0, mode="replay", chaos={})


def test_replay_mode_missing_recording_file(tmp_path):
    runner = AgentRunner(_platform(),
                         recording_path=str(tmp_path / "nope.json"))
    with pytest.raises(RecordingError, match="not found"):
        runner.run(version=_version(), scenario=_scenario(),
                   trial_index=0, mode="replay", chaos={})


def test_replay_mode_incompatible_recording(tmp_path):
    path = _write_recording(tmp_path, [_turn(0, content="done")])
    data = json.loads(path.read_text())
    data["version"]["fingerprint"] = "different-fp"
    path.write_text(json.dumps(data))
    runner = AgentRunner(_platform(), recording_path=str(path))
    with pytest.raises(RecordingError, match="fingerprint"):
        runner.run(version=_version(), scenario=_scenario(),
                   trial_index=0, mode="replay", chaos={})


# --- Fix 2: final responses are recorded ------------------------------------

def test_final_answer_turn_is_recorded(tmp_path):
    """A final-answer-only response must appear in the recording."""
    rec = TurnRecorder(version=_version(), scenario_id="happy-path-vpn",
                       trial_index=0, batch_id="batch-1", model_id="test-model")
    resp = LLMResponse(final_answer="All done.", provider="live",
                       model="test-model", input_tokens=5, output_tokens=3)
    rec.record_turn(0, resp, [])
    rec.final_answer = "All done."
    path = tmp_path / "r.json"
    rec.save(path)
    data = json.loads(path.read_text())
    assert len(data["turns"]) == 1
    assert data["turns"][0]["response"]["content"] == "All done."
    assert data["final_answer"] == "All done."


def test_multiturn_recording_replays_offline(tmp_path, monkeypatch):
    """A complete multi-turn recording replays through AgentRunner with
    networking disabled (no httpx, no platform calls)."""
    import app.llm as llm_mod
    path = _write_recording(tmp_path, [
        _turn(0,
              tool_calls=[{"id": "c1", "name": "get_ticket",
                           "arguments": {"ticketKey": "ACME-101"}}],
              executions=[{"tool": "get_ticket",
                           "args": {"ticketKey": "ACME-101"},
                           "status": "OK",
                           "result": {"ticket": {"key": "ACME-101",
                                                 "status": "open"}}}]),
        _turn(1, content="Ticket ACME-101 is open."),
    ])
    # Networking disabled at the httpx layer.
    def boom(*a, **kw):
        raise AssertionError("network call attempted during replay")
    monkeypatch.setattr(llm_mod.httpx, "post", boom)
    monkeypatch.setattr(llm_mod.httpx, "get", boom)

    platform = _platform()
    runner = AgentRunner(platform, recording_path=str(path))
    result = runner.run(version=_version(), scenario=_scenario(),
                        trial_index=0, mode="replay", chaos={})
    assert result["final_answer"] == "Ticket ACME-101 is open."
    assert result["tool_call_count"] == 1
    # No platform tool calls: results came from the recording.
    platform.tool_execute.assert_not_called()


# --- Fix 3: no premature completion ----------------------------------------

class _ScriptedProvider:
    """Returns canned responses in order, like a model thinking aloud."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = 0

    def complete(self, *, version_name, scenario_id, step, messages, chaos):
        self.calls += 1
        return self.responses.pop(0)


def test_tool_calls_with_text_continues_loop():
    """First response: 'I will inspect the ticket' + get_ticket call.
    The runner must request another model turn and return the subsequent
    final answer — not treat the text as final."""
    provider = _ScriptedProvider([
        LLMResponse(
            tool_calls=[{"id": "c1", "name": "get_ticket",
                         "arguments": {"ticketKey": "ACME-101"}}],
            final_answer="I will inspect the ticket.",
            provider="fixture", model="fixture-1.0"),
        LLMResponse(final_answer="Ticket ACME-101 is open.",
                    provider="fixture", model="fixture-1.0"),
    ])
    platform = _platform()
    platform.tool_execute.return_value = {
        "status": "OK", "result": {"ticket": {"key": "ACME-101"}}}
    runner = AgentRunner(platform, provider=provider)
    result = runner.run(version=_version(), scenario=_scenario(),
                        trial_index=0, mode="fixture", chaos={})
    assert provider.calls == 2
    assert result["final_answer"] == "Ticket ACME-101 is open."
    assert result["failure_reason"] is None
    platform.tool_execute.assert_called_once()


# --- Fix 4: recorded tool-result playback -----------------------------------

def test_replay_tool_executor_returns_recorded_result(tmp_path):
    path = _write_recording(tmp_path, [
        _turn(0,
              tool_calls=[{"id": "c1", "name": "get_ticket",
                           "arguments": {"ticketKey": "ACME-101"}}],
              executions=[{"tool": "get_ticket",
                           "args": {"ticketKey": "ACME-101"},
                           "status": "OK",
                           "result": {"ticket": {"status": "open"}}}]),
    ])
    ex = ReplayToolExecutor(path)
    out = ex.execute(0, "get_ticket", {"ticketKey": "ACME-101"})
    assert out["status"] == "OK"
    assert out["result"]["ticket"]["status"] == "open"
    assert out["idempotentReplay"] is True


def test_replay_tool_executor_rejects_incomplete_recording(tmp_path):
    path = _write_recording(tmp_path, [
        _turn(0,
              tool_calls=[{"id": "c1", "name": "get_ticket",
                           "arguments": {"ticketKey": "ACME-101"}}],
              executions=[{"tool": "get_ticket",
                           "args": {"ticketKey": "ACME-101"},
                           "status": "OK"}]),  # no "result" key
    ])
    with pytest.raises(RecordingError, match="incomplete"):
        ReplayToolExecutor(path)


def test_replay_tool_executor_rejects_unknown_call(tmp_path):
    path = _write_recording(tmp_path, [_turn(0, content="done")])
    ex = ReplayToolExecutor(path)
    with pytest.raises(RecordingError, match="no matching tool execution"):
        ex.execute(0, "get_ticket", {"ticketKey": "ACME-101"})


def test_recording_sanitizes_tool_results(tmp_path):
    from app.replay import _sanitize_result
    result = {"ticket": {"key": "ACME-101"},
              "api_key": "secret-value",
              "nested": {"password": "pw", "status": "open"}}
    clean = _sanitize_result(result)
    assert clean["ticket"]["key"] == "ACME-101"
    assert "api_key" not in clean
    assert "password" not in clean["nested"]
    assert clean["nested"]["status"] == "open"
