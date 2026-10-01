"""LLM provider abstraction: deterministic fixture vs live OpenAI-compatible API.

Fixture mode is labeled `mode: fixture` at every layer (ADR-0004).
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
    tool_calls: list[dict] = field(default_factory=list)  # {"id","name","arguments"}
    final_answer: str | None = None
    citations: list[dict] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    provider: str = "fixture"
    model: str = ""  # the actual model identifier that produced this turn


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
    """OpenAI-compatible chat completions provider (configurable).

    Uses provider-native structured tool calling: the six agent tools are
    sent as OpenAI `tools` with JSON-Schema argument definitions, and the
    model's `tool_calls` are parsed natively. Providers that do not support
    native tool calling fall back to the documented ```tool_call JSON-block
    convention (the system prompt describes it).
    """

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
        body = {
            "model": self.model,
            "messages": messages,
            "temperature": 0,
            "tools": NATIVE_TOOL_DEFINITIONS,
            "tool_choice": "auto",
        }
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
        # Prefer native tool calls; fall back to the text convention for
        # providers without structured calling support.
        tool_calls = parse_native_tool_calls(choice.get("tool_calls") or [])
        if not tool_calls:
            parsed, _ = parse_model_output(content)
            tool_calls = parsed
            final = content.strip() or None
        else:
            final = content.strip() or None
        # A turn that only calls tools has no final answer yet.
        if tool_calls and not final:
            final = None
        return LLMResponse(tool_calls=tool_calls, final_answer=final,
                           input_tokens=usage.get("prompt_tokens", 0),
                           output_tokens=usage.get("completion_tokens", 0),
                           provider="live", model=self.model)


# OpenAI function-calling format definitions for the six agent tools.
# These are sent natively with every live request (tool_choice=auto).
NATIVE_TOOL_DEFINITIONS = [
    {"type": "function", "function": {
        "name": "search_runbooks",
        "description": "Search the runbooks you are permitted to read.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "Search query"},
            "topK": {"type": "integer", "minimum": 1, "maximum": 10}},
         "required": ["query"]}}},
    {"type": "function", "function": {
        "name": "get_ticket",
        "description": "Read a service-desk ticket by key.",
        "parameters": {"type": "object", "properties": {
            "ticketKey": {"type": "string", "description": "Ticket key, e.g. ACME-101"}},
         "required": ["ticketKey"]}}},
    {"type": "function", "function": {
        "name": "update_ticket_status",
        "description": "Change a ticket's status.",
        "parameters": {"type": "object", "properties": {
            "ticketKey": {"type": "string"},
            "status": {"type": "string", "enum": ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"]}},
         "required": ["ticketKey", "status"]}}},
    {"type": "function", "function": {
        "name": "get_service_status",
        "description": "Read live service health.",
        "parameters": {"type": "object", "properties": {
            "serviceName": {"type": "string", "description": "Service name; omit for all services"}}}}},
    {"type": "function", "function": {
        "name": "request_access",
        "description": "Propose an access change. Creates a PENDING approval; a human must approve before it can be executed. Never claim the change is complete until execute_approval succeeds.",
        "parameters": {"type": "object", "properties": {
            "targetUsername": {"type": "string"},
            "resource": {"type": "string"},
            "reason": {"type": "string"},
            "ticketKey": {"type": "string"}},
         "required": ["targetUsername", "resource"]}}},
    {"type": "function", "function": {
        "name": "execute_approval",
        "description": "Execute an approved access change. Only call with an approvalId that was APPROVED by a human reviewer.",
        "parameters": {"type": "object", "properties": {
            "approvalId": {"type": "string", "format": "uuid"}},
         "required": ["approvalId"]}}},
]


def parse_native_tool_calls(raw_calls: list[dict]) -> list[dict]:
    """Convert OpenAI-format tool_calls to the internal {id,name,arguments} form."""
    import json
    out = []
    for tc in raw_calls:
        fn = tc.get("function", {}) or {}
        name = fn.get("name", "")
        args = fn.get("arguments", {})
        if isinstance(args, str):
            try:
                args = json.loads(args) if args.strip() else {}
            except json.JSONDecodeError:
                args = {}
        out.append({"id": tc.get("id") or f"call_{len(out)}",
                    "name": name, "arguments": args or {}})
    return out


def validate_live_config() -> str:
    """Fail fast before a live evaluation if the provider is not configured.

    Returns the configured model identifier. Raises RuntimeError with a
    precise missing-piece message otherwise.
    """
    missing = []
    if not settings.llm_base_url:
        missing.append("LLM_BASE_URL")
    if not settings.llm_model:
        missing.append("LLM_MODEL")
    if missing:
        raise RuntimeError(
            "Live evaluation not configured: missing " + ", ".join(missing) +
            ". Set them in the environment (see .env.example). "
            "Fixture mode remains available with --mode fixture.")
    return settings.llm_model


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
