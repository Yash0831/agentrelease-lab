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


def _write_recording(tmp_path, turns, schema_version=2):
    """Write a recording JSON directly (bypasses TurnRecorder)."""
    data = {
        "schema_version": schema_version,
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


def _turn(step, tool_calls=None, content=None, executions=None,
          citations=None):
    return {
        "step": step,
        "response": {
            "tool_calls": tool_calls or [],
            "content": content,
            "citations": citations if citations is not None else [],
            "input_tokens": 10, "output_tokens": 5,
            "provider": "live", "model": "test-model",
        },
        "tool_executions": [
            {"tool": e["tool"], "args": e["args"], "status": e["status"],
             "error_code": e.get("error_code", ""),
             "error_message": e.get("error_message", ""),
             "result": e.get("result", {})}
            for e in (executions or [])
        ],
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
    # Raw recording: a tool execution without a "result" key (bypasses the
    # _turn helper, which normalizes keys).
    raw_turn = {
        "step": 0,
        "response": {"tool_calls": [{"id": "c1", "name": "get_ticket",
                                     "arguments": {"ticketKey": "ACME-101"}}],
                     "content": None, "citations": [],
                     "input_tokens": 10, "output_tokens": 5,
                     "provider": "live", "model": "test-model"},
        "tool_executions": [{"tool": "get_ticket",
                             "args": {"ticketKey": "ACME-101"},
                             "status": "OK",
                             "error_code": "", "error_message": ""}],
    }
    path = _write_recording(tmp_path, [raw_turn])
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


# --- Fix 1 (round 2): citations and tool error fields are preserved --------

def test_replay_restores_citations(tmp_path):
    """A response recorded with one citation must replay with that citation,
    not zero citations."""
    from app.replay import ReplayLLM
    citations = [{"slug": "vpn-reset", "version": 3,
                  "claim": "reset the VPN client"}]
    path = _write_recording(tmp_path, [
        _turn(0, content="Reset the client per the runbook.",
              citations=citations),
    ])
    llm = ReplayLLM(path)
    r = llm.complete(version_name="baseline", scenario_id="happy-path-vpn",
                     step=0, messages=[], chaos={})
    assert r.citations == citations


def test_replay_tool_executor_returns_error_fields(tmp_path):
    """A recorded TICKET_NOT_FOUND error must replay with its error code
    and message, not an empty error code."""
    path = _write_recording(tmp_path, [
        _turn(0,
              tool_calls=[{"id": "c1", "name": "get_ticket",
                           "arguments": {"ticketKey": "T-999"}}],
              executions=[{"tool": "get_ticket",
                           "args": {"ticketKey": "T-999"},
                           "status": "ERROR",
                           "error_code": "TICKET_NOT_FOUND",
                           "error_message": "ticket T-999 not found"}]),
    ])
    ex = ReplayToolExecutor(path)
    out = ex.execute(0, "get_ticket", {"ticketKey": "T-999"})
    assert out["status"] == "ERROR"
    assert out["errorCode"] == "TICKET_NOT_FOUND"
    assert out["errorMessage"] == "ticket T-999 not found"


def test_legacy_schema1_recording_rejected_explicitly(tmp_path):
    """Schema-1 recordings lack citations and error fields. Replay must
    reject them with a clear message, not silently replay empty evidence."""
    from app.replay import ReplayLLM
    legacy_turn = {
        "step": 0,
        "response": {"tool_calls": [], "content": "done",
                     "input_tokens": 1, "output_tokens": 1,
                     "provider": "live", "model": "test-model"},
        "tool_executions": [{"tool": "get_ticket", "args": {},
                             "status": "OK", "result": {}}],
    }
    path = _write_recording(tmp_path, [legacy_turn], schema_version=1)
    with pytest.raises(RecordingError, match="older|re-record"):
        ReplayLLM(path)
    with pytest.raises(RecordingError, match="older|re-record"):
        ReplayToolExecutor(path)


def test_recording_sanitizes_error_message():
    from app.replay import _sanitize_text
    assert _sanitize_text("ticket T-999 not found") == "ticket T-999 not found"
    redacted = _sanitize_text("gateway refused: api_key=abc123 leaked")
    assert "abc123" not in redacted
    assert "api_key=<redacted>" in redacted
    assert _sanitize_text(None) == ""


def test_roundtrip_record_and_replay_preserves_evidence(tmp_path, monkeypatch):
    """Full round trip through AgentRunner: execute (record), then replay.
    Citations, tool outcomes, and error codes must match; replay must make
    no live model calls and no business mutations."""
    import app.llm as llm_mod
    import app.replay as replay_mod
    from app.replay import TurnRecorder

    citations = [{"slug": "vpn-reset", "version": 3}]
    provider = _ScriptedProvider([
        LLMResponse(
            tool_calls=[{"id": "c1", "name": "search_runbooks",
                         "arguments": {"query": "vpn"}}],
            citations=citations, provider="fixture", model="fixture-1.0"),
        LLMResponse(
            tool_calls=[{"id": "c2", "name": "get_ticket",
                         "arguments": {"ticketKey": "T-999"}}],
            provider="fixture", model="fixture-1.0"),
        LLMResponse(final_answer="Ticket T-999 was not found.",
                    citations=citations,
                    provider="fixture", model="fixture-1.0"),
    ])

    def boom(*a, **kw):
        raise AssertionError("network call attempted")

    def platform_v1():
        p = _platform()

        def tool_execute(*, tool, args, idempotency_key, eval_run_id,
                         trace_id):
            if tool == "search_runbooks":
                return {"status": "OK",
                        "result": {"results": [{"slug": "vpn-reset",
                                                "version": 3,
                                                "title": "VPN reset"}]}}
            if tool == "get_ticket":
                return {"status": "ERROR",
                        "errorCode": "TICKET_NOT_FOUND",
                        "errorMessage": "ticket T-999 not found",
                        "result": {}}
            raise AssertionError(f"unexpected tool {tool}")

        p.tool_execute.side_effect = tool_execute
        return p

    monkeypatch.setattr(replay_mod, "RECORDINGS_DIR", tmp_path)
    recorder = TurnRecorder(version=_version(), scenario_id="happy-path-vpn",
                            trial_index=0, batch_id="batch-1",
                            model_id="fixture-1.0", mode="fixture")
    runner1 = AgentRunner(platform_v1(), provider=provider, recorder=recorder)
    result1 = runner1.run(version=_version(), scenario=_scenario(),
                          trial_index=0, mode="fixture", chaos={})
    assert result1["failure_reason"] is None
    assert result1["citations"] == citations
    recording_path = result1["recording_path"]
    assert recording_path is not None

    # The recording itself carries the evidence.
    data = json.loads(Path(recording_path).read_text())
    assert data["turns"][0]["response"]["citations"] == citations
    err_exec = data["turns"][1]["tool_executions"][0]
    assert err_exec["status"] == "ERROR"
    assert err_exec["error_code"] == "TICKET_NOT_FOUND"
    assert "T-999" in err_exec["error_message"]

    # Replay with a hostile platform: any tool_execute call fails the test.
    monkeypatch.setattr(llm_mod.httpx, "post", boom)
    monkeypatch.setattr(llm_mod.httpx, "get", boom)
    platform2 = _platform()
    platform2.tool_execute.side_effect = boom
    runner2 = AgentRunner(platform2, recording_path=recording_path)
    result2 = runner2.run(version=_version(), scenario=_scenario(),
                          trial_index=0, mode="replay", chaos={})

    assert result2["failure_reason"] is None
    assert result2["final_answer"] == result1["final_answer"]
    assert result2["citations"] == result1["citations"] == citations
    assert result2["tool_call_count"] == result1["tool_call_count"] == 2
    # The replayed error outcome matches the original.
    tool_results = [e for e in result2["events"] if e["kind"] == "tool_result"]
    by_tool = {e["name"]: e["payload"] for e in tool_results}
    assert by_tool["get_ticket"]["status"] == "ERROR"
    assert by_tool["get_ticket"]["error_code"] == "TICKET_NOT_FOUND"
    assert by_tool["search_runbooks"]["status"] == "OK"
    # No live model calls, no business mutations.
    platform2.tool_execute.assert_not_called()
