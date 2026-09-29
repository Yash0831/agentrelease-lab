"""Agent loop test with a fake platform: verifies tool dispatch, trace events,
and citation tracking without any network."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.agent import AgentRunner
from app.llm import FixtureLLM


class FakePlatform:
    def __init__(self):
        self.api_key = "test-key"
        self.tool_calls = []
        self.runs = {}
        self.n = 0

    def eval_run_create(self, **kw):
        self.n += 1
        rid = f"run-{self.n}"
        self.runs[rid] = kw
        return {"id": rid, "status": "RUNNING"}

    def eval_run_finish(self, run_id, status, metrics, error=None):
        self.runs[run_id]["finished"] = (status, metrics)
        return {"id": run_id, "status": status}

    def tool_execute(self, *, tool, args, idempotency_key,
                     eval_run_id=None, trace_id=None):
        self.tool_calls.append((tool, args, idempotency_key))
        if tool == "search_runbooks":
            return {"status": "OK", "errorCode": "", "idempotentReplay": False,
                    "result": {"results": [
                        {"slug": "vpn-troubleshooting", "version": 3,
                         "snippet": "check status first"}]}}
        if tool == "get_ticket":
            return {"status": "OK", "errorCode": "", "idempotentReplay": False,
                    "result": {"ticketKey": "ACME-101", "status": "OPEN"}}
        if tool == "update_ticket_status":
            return {"status": "OK", "errorCode": "", "idempotentReplay": False,
                    "result": {"ticketKey": "ACME-101", "status": "IN_PROGRESS"}}
        return {"status": "TOOL_UNKNOWN", "errorCode": "TOOL_UNKNOWN",
                "idempotentReplay": False, "result": {}}

    def trace_events(self, eval_run_id, events):
        self.runs[eval_run_id]["events"] = events
        return {"ingested": len(events)}


def test_agent_loop_executes_fixture_script():
    platform = FakePlatform()
    runner = AgentRunner(platform, provider=FixtureLLM("fixtures/fixture_responses.yaml"))
    version = {"id": "v1", "name": "baseline", "model_id": "fixture-1.0",
               "fingerprint": "abc", "prompt": "test prompt"}
    scenario = {"id": "happy-path-vpn", "task": "Triage ACME-101."}
    result = runner.run(version=version, scenario=scenario, trial_index=0,
                        mode="fixture", chaos={})
    tools = [t for t, _, _ in platform.tool_calls]
    assert tools == ["search_runbooks", "get_ticket", "update_ticket_status"], tools
    assert result["final_answer"] is not None
    assert result["citations"] == [{"slug": "vpn-troubleshooting", "version": 3}]
    assert len(result["retrieved_hits"]) == 1
    kinds = [e["kind"] for e in result["events"]]
    assert "run" in kinds and "llm_call" in kinds and "tool_result" in kinds
    # Idempotency keys are unique per (run, step, tool, args).
    keys = [k for _, _, k in platform.tool_calls]
    assert len(set(keys)) == len(keys)


def test_flawed_candidate_unknown_tool_is_recorded():
    platform = FakePlatform()
    runner = AgentRunner(platform, provider=FixtureLLM("fixtures/fixture_responses.yaml"))
    version = {"id": "v2", "name": "candidate-flawed", "model_id": "fixture-1.0",
               "fingerprint": "def", "prompt": "x"}
    scenario = {"id": "prompt-injection-doc", "task": "Handle ACME-102."}
    result = runner.run(version=version, scenario=scenario, trial_index=0,
                        mode="fixture", chaos={})
    tools = [t for t, _, _ in platform.tool_calls]
    assert "grant_access" in tools  # not in the gateway allowlist
    statuses = [e["payload"]["status"]
                for e in result["events"] if e["kind"] == "tool_result"]
    assert "TOOL_UNKNOWN" in statuses
    assert "mallory" in (result["final_answer"] or "")
