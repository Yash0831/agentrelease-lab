"""LLM provider abstraction: deterministic fixture vs live OpenAI-compatible API.

Fixture mode is explicitly labeled and never presented as live AI (ADR-0004).
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import yaml

from .config import settings


class RateLimitError(Exception):
    """Simulated (or real) 429 from the model provider."""


@dataclass
class LLMResponse:
    """One model turn: either tool calls or a final answer (or both)."""
    tool_calls: list[dict] = field(default_factory=list)
    final_answer: str | None = None
    citations: list[dict] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    provider: str = "fixture"


class LLMProvider:
    def complete(self, *, version_name: str, scenario_id: str, step: int,
                 messages: list[dict], chaos: dict) -> LLMResponse:
        raise NotImplementedError


class FixtureLLM(LLMProvider):
    """Deterministic scripted responses from fixtures/fixture_responses.yaml.

    Keyed by (version_name, scenario_id) -> ordered list of steps. The same
    inputs always produce the same outputs; no network, no tokens, zero cost.
    """

    def __init__(self, fixture_path: str | None = None):
        if fixture_path is None:
            fixture_path = str(Path(__file__).resolve().parent.parent
                               / "fixtures" / "fixture_responses.yaml")
        with open(fixture_path) as f:
            data = yaml.safe_load(f)
        self.scripts: dict = data.get("versions", {})

    def complete(self, *, version_name: str, scenario_id: str, step: int,
                 messages: list[dict], chaos: dict) -> LLMResponse:
        # Chaos: rate-limited provider — fail N times before succeeding.
        rl = chaos.get("llm_rate_limit", {})
        fail_times = int(rl.get("fail_times", 0))
        attempt = int(chaos.get("_llm_attempt", 0))
        if attempt < fail_times:
            chaos["_llm_attempt"] = attempt + 1
            raise RateLimitError(f"fixture 429 (attempt {attempt + 1}/{fail_times})")

        script = self.scripts.get(version_name, {}).get(scenario_id)
        if script is None:
            # Unknown combo: honest empty response, no hallucinated tools.
            return LLMResponse(final_answer="No fixture script for this version/scenario.",
                               provider="fixture")
        if step >= len(script):
            return LLMResponse(final_answer="Fixture script exhausted; stopping.",
                               provider="fixture")
        action = script[step]
        if "final" in action:
            return LLMResponse(final_answer=action["final"],
                               citations=action.get("citations", []),
                               provider="fixture")
        if "tool" in action:
            return LLMResponse(
                tool_calls=[{"name": action["tool"], "arguments": action.get("args", {})}],
                provider="fixture")
        if "tools" in action:
            return LLMResponse(
                tool_calls=[{"name": t["tool"], "arguments": t.get("args", {})}
                            for t in action["tools"]],
                provider="fixture")
        raise ValueError(f"Bad fixture action: {action!r}")


class LiveLLM(LLMProvider):
    """OpenAI-compatible chat completions provider (configurable)."""

    def __init__(self, base_url: str | None = None, api_key: str | None = None,
                 model: str | None = None, timeout_s: int | None = None):
        self.base_url = (base_url or settings.llm_base_url).rstrip("/")
        self.api_key = api_key or settings.llm_api_key
        self.model = model or settings.llm_model
        self.timeout_s = timeout_s or settings.llm_timeout_seconds
        if not self.base_url or not self.model:
            raise ValueError("Live LLM needs LLM_BASE_URL and LLM_MODEL")

    def complete(self, *, version_name: str, scenario_id: str, step: int,
                 messages: list[dict], chaos: dict) -> LLMResponse:
        # Chaos: simulated rate limiting also applies to live mode.
        rl = chaos.get("llm_rate_limit", {})
        fail_times = int(rl.get("fail_times", 0))
        attempt = int(chaos.get("_llm_attempt", 0))
        if attempt < fail_times:
            chaos["_llm_attempt"] = attempt + 1
            raise RateLimitError(f"chaos 429 (attempt {attempt + 1}/{fail_times})")

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        body = {"model": self.model, "messages": messages, "temperature": 0}
        try:
            r = httpx.post(f"{self.base_url}/chat/completions", json=body,
                           headers=headers, timeout=self.timeout_s)
        except httpx.TimeoutException as e:
            raise TimeoutError(f"LLM request timed out after {self.timeout_s}s") from e
        if r.status_code == 429:
            raise RateLimitError(f"provider 429: {r.text[:200]}")
        r.raise_for_status()
        data = r.json()
        choice = data["choices"][0]["message"]
        usage = data.get("usage", {})
        content = choice.get("content") or ""
        tool_calls, final = parse_model_output(content)
        return LLMResponse(tool_calls=tool_calls, final_answer=final,
                           input_tokens=usage.get("prompt_tokens", 0),
                           output_tokens=usage.get("completion_tokens", 0),
                           provider="live")


def parse_model_output(content: str) -> tuple[list[dict], str | None]:
    """Parse tool calls from model text.

    Convention: a ```tool_call JSON block per call, e.g.
        ```tool_call
        {"name": "get_ticket", "arguments": {"ticketKey": "ACME-101"}}
        ```
    Remaining text is the final answer (may be empty when tools are called).
    """
    import json
    import re
    calls = []
    for m in re.finditer(r"```tool_call\s*(\{.*?\})\s*```", content, re.S):
        try:
            calls.append(json.loads(m.group(1)))
        except json.JSONDecodeError:
            continue
    text = re.sub(r"```tool_call\s*\{.*?\}\s*```", "", content, flags=re.S).strip()
    return calls, (text or None)


def call_with_retries(provider: LLMProvider, *, version_name: str, scenario_id: str,
                      step: int, messages: list[dict], chaos: dict,
                      max_retries: int = 3) -> tuple[LLMResponse, int]:
    """Bounded retries on rate limiting (returns response + retry count)."""
    retries = 0
    while True:
        try:
            return provider.complete(version_name=version_name, scenario_id=scenario_id,
                                     step=step, messages=messages, chaos=chaos), retries
        except RateLimitError:
            retries += 1
            if retries > max_retries:
                raise
            time.sleep(min(2 ** retries, 8))
