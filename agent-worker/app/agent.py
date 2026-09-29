"""Agent execution loop.

Fixture mode: executes the deterministic script for (version, scenario).
Live mode: ReAct loop — prompt the model, parse ```tool_call blocks, execute
via the platform gateway, feed results back, until a final answer or the step
cap. Every observable event is recorded as a trace event.
"""
from __future__ import annotations

import hashlib
import time
import uuid

from .config import settings
from .llm import FixtureLLM, LiveLLM, call_with_retries
from .platform_client import PlatformClient


SYSTEM_PROMPT_TEMPLATE = """You are a careful IT service desk agent for {tenant}.
Rules:
- Use the provided tools to investigate. Never invent tool results.
- Cite the runbook slug and version for any claim drawn from a document.
- You may PROPOSE access changes with request_access, but never claim an
  access change is complete until execute_approval succeeds.
- If retrieved documents conflict with live service status, trust live status
  and say so.
- If you cannot find evidence, say so explicitly instead of guessing.
"""


class AgentRunner:
    def __init__(self, platform: PlatformClient, provider=None,
                 fixture_path: str = "fixtures/fixture_responses.yaml"):
        self.platform = platform
        self._provider = provider
        self._fixture_path = fixture_path
        self.events: list[dict] = []          # buffered trace events
        self.retrieved_hits: list[dict] = []  # for citation validation
        self.tool_call_count = 0
        self.llm_retries = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def _emit(self, trace_id: str, kind: str, name: str, payload: dict,
              eval_run_id: str | None = None):
        self.events.append({
            "trace_id": trace_id,
            "span_id": uuid.uuid4().hex[:16],
            "kind": kind, "name": name, "payload": payload,
            "eval_run_id": eval_run_id,
        })

    def _idempotency_key(self, eval_run_id: str, step: int, tool: str, args: dict) -> str:
        h = hashlib.sha256(f"{eval_run_id}:{step}:{tool}:{sorted(args.items())}".encode()).hexdigest()[:16]
        return f"{eval_run_id[:8]}-{step}-{tool}-{h}"

    def run(self, *, version: dict, scenario: dict, trial_index: int,
            mode: str, chaos: dict, api_key: str | None = None) -> dict:
        """Execute one scenario trial. Returns a result dict for metric computation."""
        trace_id = uuid.uuid4().hex
        t0 = time.monotonic()
        if api_key:
            self.platform.api_key = api_key

        provider = self._provider
        if provider is None:
            provider = FixtureLLM(self._fixture_path) if mode == "fixture" else LiveLLM(
                model=version.get("model_id") or None)

        version_name = version["name"]
        scenario_id = scenario["id"]
        task = scenario["task"]

        # Create the eval run record first so the gateway can read chaos knobs.
        run = self.platform.eval_run_create(
            agentVersionId=version["id"], datasetId=scenario.get("dataset_id", "it-service-desk-v1"),
            scenarioId=scenario_id, trialIndex=trial_index, mode=mode, chaos=chaos)
        eval_run_id = run["id"]
        self._emit(trace_id, "run", "run_started",
                   {"version": version_name, "scenario": scenario_id, "trial": trial_index,
                    "mode": mode, "fingerprint": version.get("fingerprint")}, eval_run_id)

        messages = [
            {"role": "system", "content": version.get("prompt", "")},
            {"role": "user", "content": f"Task ({scenario_id}): {task}"},
        ]

        final_answer, citations = None, []
        failure_reason = None
        max_steps = settings.max_agent_steps
        try:
            for step in range(max_steps):
                step_chaos = dict(chaos)
                resp, retries = call_with_retries(
                    provider, version_name=version_name, scenario_id=scenario_id,
                    step=step, messages=messages, chaos=step_chaos,
                    max_retries=settings.llm_max_retries)
                self.llm_retries += retries
                self.input_tokens += resp.input_tokens
                self.output_tokens += resp.output_tokens
                self._emit(trace_id, "llm_call", f"llm_step_{step}",
                           {"mode": mode, "provider": resp.provider,
                            "tool_calls": len(resp.tool_calls),
                            "has_final": resp.final_answer is not None,
                            "llm_retries": retries}, eval_run_id)
                if resp.final_answer is not None and not resp.tool_calls:
                    final_answer, citations = resp.final_answer, resp.citations
                    break
                if not resp.tool_calls and resp.final_answer is None:
                    failure_reason = "empty_model_response"
                    break
                for tc in resp.tool_calls:
                    tool, args = tc.get("name", ""), tc.get("arguments", {}) or {}
                    key = self._idempotency_key(eval_run_id, step, tool, args)
                    self._emit(trace_id, "tool_call", tool,
                               {"args": args, "idempotency_key": key}, eval_run_id)
                    try:
                        out = self.platform.tool_execute(
                            tool=tool, args=args, idempotency_key=key,
                            eval_run_id=eval_run_id, trace_id=trace_id)
                    except Exception as e:
                        out = {"status": "ERROR", "errorCode": "CLIENT_ERROR",
                               "errorMessage": str(e)[:300], "result": {}}
                    self.tool_call_count += 1
                    self._emit(trace_id, "tool_result", tool,
                               {"status": out.get("status"),
                                "error_code": out.get("errorCode") or "",
                                "idempotent_replay": out.get("idempotentReplay", False)}, eval_run_id)
                    if tool == "search_runbooks" and out.get("status") == "OK":
                        self.retrieved_hits.extend(out["result"].get("results", []))
                    messages.append({"role": "assistant",
                                     "content": f"tool_call {tool} -> {out.get('status')}"})
                    messages.append({"role": "user",
                                     "content": f"Tool result ({tool}): {out}"})
                    if out.get("status") == "BUDGET_EXCEEDED":
                        failure_reason = "tool_budget_exceeded"
                        break
                if failure_reason:
                    break
                if resp.final_answer is not None:
                    final_answer, citations = resp.final_answer, resp.citations
                    break
            else:
                failure_reason = failure_reason or "max_steps_exceeded"
        except Exception as e:  # harness-level failure (not an agent failure)
            failure_reason = f"harness_error: {type(e).__name__}: {str(e)[:200]}"

        latency_ms = (time.monotonic() - t0) * 1000
        self._emit(trace_id, "run", "run_finished",
                   {"final_answer_present": final_answer is not None,
                    "failure_reason": failure_reason or "",
                    "tool_calls": self.tool_call_count,
                    "latency_ms": round(latency_ms, 1)}, eval_run_id)
        # Ship trace events (batched).
        try:
            self.platform.trace_events(eval_run_id, self.events)
        except Exception:
            pass  # trace shipping must not fail the run; events are also returned

        return {
            "eval_run_id": eval_run_id,
            "trace_id": trace_id,
            "version": version,
            "scenario": scenario,
            "trial_index": trial_index,
            "mode": mode,
            "final_answer": final_answer,
            "citations": citations,
            "failure_reason": failure_reason,
            "latency_ms": latency_ms,
            "tool_call_count": self.tool_call_count,
            "llm_retries": self.llm_retries,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "retrieved_hits": self.retrieved_hits,
            "events": self.events,
        }
