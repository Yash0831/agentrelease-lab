"""Worker tests: fixture determinism, retry behavior, honesty of unknown scripts."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.llm import FixtureLLM, RateLimitError, call_with_retries

FIXTURES = "fixtures/fixture_responses.yaml"


def test_fixture_is_deterministic():
    llm = FixtureLLM(FIXTURES)
    kw = dict(version_name="baseline", scenario_id="happy-path-vpn",
              step=0, messages=[], chaos={})
    a = llm.complete(**kw)
    b = llm.complete(**kw)
    assert a.tool_calls == b.tool_calls
    assert a.tool_calls[0]["name"] == "search_runbooks"


def test_fixture_rate_limit_then_recovers_with_bounded_retries():
    llm = FixtureLLM(FIXTURES)
    chaos = {"llm_rate_limit": {"fail_times": 2}}
    resp, retries = call_with_retries(
        llm, version_name="baseline", scenario_id="rate-limited-provider",
        step=0, messages=[], chaos=chaos, max_retries=3)
    assert retries == 2
    assert resp.tool_calls[0]["name"] == "get_ticket"


def test_fixture_rate_limit_exhaustion_raises():
    llm = FixtureLLM(FIXTURES)
    chaos = {"llm_rate_limit": {"fail_times": 10}}
    with pytest.raises(RateLimitError):
        call_with_retries(llm, version_name="baseline",
                          scenario_id="rate-limited-provider", step=0,
                          messages=[], chaos=chaos, max_retries=3)


def test_unknown_script_is_honest_not_hallucinated():
    llm = FixtureLLM(FIXTURES)
    resp = llm.complete(version_name="nope", scenario_id="nope", step=0,
                        messages=[], chaos={})
    assert resp.tool_calls == []
    assert resp.final_answer is not None


def test_parse_native_tool_calls_openai_format():
    from app.llm import parse_native_tool_calls
    raw = [{
        "id": "call_abc123",
        "type": "function",
        "function": {
            "name": "get_ticket",
            "arguments": '{"ticketKey": "ACME-101"}',
        },
    }]
    out = parse_native_tool_calls(raw)
    assert out == [{"id": "call_abc123", "name": "get_ticket",
                    "arguments": {"ticketKey": "ACME-101"}}]


def test_parse_native_tool_calls_dict_arguments():
    from app.llm import parse_native_tool_calls
    raw = [{"id": "call_1", "type": "function",
            "function": {"name": "search_runbooks",
                         "arguments": {"query": "vpn", "topK": 3}}}]
    out = parse_native_tool_calls(raw)
    assert out[0]["arguments"] == {"query": "vpn", "topK": 3}


def test_parse_native_tool_calls_bad_json_arguments():
    from app.llm import parse_native_tool_calls
    raw = [{"id": "call_1", "type": "function",
            "function": {"name": "get_ticket", "arguments": "{not json"}}]
    out = parse_native_tool_calls(raw)
    assert out[0]["arguments"] == {}


def test_native_tool_definitions_cover_all_agent_tools():
    from app.llm import NATIVE_TOOL_DEFINITIONS
    names = {t["function"]["name"] for t in NATIVE_TOOL_DEFINITIONS}
    assert names == {"search_runbooks", "get_ticket", "update_ticket_status",
                     "get_service_status", "request_access", "execute_approval"}
    for t in NATIVE_TOOL_DEFINITIONS:
        assert t["type"] == "function"
        assert t["function"]["parameters"]["type"] == "object"


def test_validate_live_config_missing_raises():
    from app import llm as llm_mod
    orig_base, orig_model = llm_mod.settings.llm_base_url, llm_mod.settings.llm_model
    llm_mod.settings.llm_base_url = ""
    llm_mod.settings.llm_model = ""
    try:
        import pytest as _pytest
        with _pytest.raises(RuntimeError, match="LLM_BASE_URL"):
            llm_mod.validate_live_config()
    finally:
        llm_mod.settings.llm_base_url = orig_base
        llm_mod.settings.llm_model = orig_model


def test_live_llm_sends_tools_and_parses_native_calls(monkeypatch):
    import json as _json
    from app import llm as llm_mod
    from app.llm import LiveLLM

    monkeypatch.setattr(llm_mod.settings, "llm_base_url", "http://llm.test")
    monkeypatch.setattr(llm_mod.settings, "llm_model", "test-model-1")

    captured = {}

    class FakeResp:
        status_code = 200
        def raise_for_status(self): pass
        def json(self):
            return {"choices": [{"message": {
                "content": "",
                "tool_calls": [{
                    "id": "call_xyz", "type": "function",
                    "function": {"name": "get_ticket",
                                 "arguments": _json.dumps({"ticketKey": "ACME-101"})}}]}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5}}

    def fake_post(url, json=None, headers=None, timeout=None):
        captured["body"] = json
        return FakeResp()

    monkeypatch.setattr(llm_mod.httpx, "post", fake_post)
    provider = LiveLLM()
    resp = provider.complete(version_name="v", scenario_id="s", step=0,
                             messages=[{"role": "user", "content": "hi"}], chaos={})
    # Real model identifier flows through, not a placeholder.
    assert captured["body"]["model"] == "test-model-1"
    assert resp.model == "test-model-1"
    # Native tool definitions were sent.
    sent_names = {t["function"]["name"] for t in captured["body"]["tools"]}
    assert "get_ticket" in sent_names
    # Native tool call parsed with its id preserved for tool-result messages.
    assert resp.tool_calls == [{"id": "call_xyz", "name": "get_ticket",
                                "arguments": {"ticketKey": "ACME-101"}}]
    assert resp.final_answer is None
    assert resp.provider == "live"
