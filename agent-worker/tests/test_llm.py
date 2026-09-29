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
