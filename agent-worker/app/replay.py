"""Recorded-response replay for live evaluations.

A live run can be recorded (model responses, tool calls, document versions,
configuration) and later replayed without any LLM provider calls. Replay is
deterministic: the recorded model decisions are returned in order, while tools
are re-executed through the local gateway (no external side effects).

Recording format (JSON, schema_version=1):
{
  "schema_version": 1,
  "mode": "live",
  "model_id": "<actual model identifier>",
  "version": {"name": ..., "fingerprint": ...},
  "scenario_id": "...", "trial_index": 0, "batch_id": "...",
  "recorded_at": "<iso8601>",
  "config": {"temperature": 0, "max_steps": 12},
  "turns": [
    {"step": 0,
     "response": {"tool_calls": [...], "content": "...",
                  "input_tokens": N, "output_tokens": M},
     "tool_executions": [{"tool": ..., "args": {...}, "status": "..."}]},
  ],
  "retrieved_docs": [{"slug": ..., "version": N}],
  "final_answer": "...",
}

Sanitization: recordings contain the system prompt, task text, tool names/args,
and tool result summaries. They do NOT contain API keys, Authorization headers,
or provider credentials. Tool results are summarized (status + error codes),
not full payloads, to avoid persisting sensitive data.

What replay reproduces:
- The exact sequence of model decisions (tool calls and final answers).
- Token usage and latency are NOT reproduced (replay is instant, zero cost).

What replay does NOT reproduce:
- Fresh model behavior (the model is not called).
- Tool side effects are re-executed through the gateway (they are local, not
  external). For a fully side-effect-free replay, use fixture mode.

Limits:
- A recording is bound to (version fingerprint, scenario, trial). Replaying
  against a different version or scenario is rejected.
- If the recording is truncated (fewer turns than the replay needs), replay
  fails with a clear error rather than hallucinating.
"""
from __future__ import annotations

import datetime
import json
from pathlib import Path

from .llm import LLMProvider, LLMResponse

SCHEMA_VERSION = 1
RECORDINGS_DIR = Path(__file__).resolve().parent.parent / "benchmarks" / "recordings"


class RecordingError(Exception):
    """Incomplete or incompatible recording."""


class TurnRecorder:
    """Captures a live run's model interactions for later replay."""

    def __init__(self, *, version: dict, scenario_id: str, trial_index: int,
                 batch_id: str, model_id: str, mode: str = "live"):
        self.version = version
        self.scenario_id = scenario_id
        self.trial_index = trial_index
        self.batch_id = batch_id
        self.model_id = model_id
        self.mode = mode
        self.turns: list[dict] = []
        self.retrieved_docs: list[dict] = []
        self.final_answer: str | None = None

    def record_turn(self, step: int, response: LLMResponse,
                    tool_executions: list[dict]) -> None:
        # Sanitize: keep decision-relevant data, drop full payloads.
        self.turns.append({
            "step": step,
            "response": {
                "tool_calls": response.tool_calls,
                "content": None,  # content is in final_answer or tool_calls
                "input_tokens": response.input_tokens,
                "output_tokens": response.output_tokens,
                "provider": response.provider,
                "model": response.model,
            },
            "tool_executions": [
                {"tool": t.get("tool"), "args": t.get("args"),
                 "status": t.get("status")}
                for t in tool_executions
            ],
        })
        if response.final_answer is not None:
            self.turns[-1]["response"]["content"] = response.final_answer

    def record_retrieved(self, hits: list[dict]) -> None:
        for h in hits:
            key = (h.get("slug"), h.get("version"))
            if key not in [(d["slug"], d["version"]) for d in self.retrieved_docs]:
                self.retrieved_docs.append({"slug": h.get("slug"),
                                            "version": h.get("version")})

    def save(self, path: Path | None = None) -> Path:
        if path is None:
            RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
            name = (f"{self.version['name']}_{self.scenario_id}_t{self.trial_index}_"
                    f"{self.batch_id[:8]}.json")
            # Sanitize filename
            name = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
            path = RECORDINGS_DIR / name
        data = {
            "schema_version": SCHEMA_VERSION,
            "mode": self.mode,
            "model_id": self.model_id,
            "version": {"name": self.version.get("name"),
                        "fingerprint": self.version.get("fingerprint")},
            "scenario_id": self.scenario_id,
            "trial_index": self.trial_index,
            "batch_id": self.batch_id,
            "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "config": {"temperature": 0},
            "turns": self.turns,
            "retrieved_docs": self.retrieved_docs,
            "final_answer": self.final_answer,
        }
        path.write_text(json.dumps(data, indent=2))
        return path


class ReplayLLM(LLMProvider):
    """Replays recorded model responses. Makes NO network calls.

    The recording is bound to a specific (version fingerprint, scenario,
    trial). Any mismatch — or a truncated recording — raises RecordingError
    with a clear message. This provider never contacts an LLM API.
    """

    def __init__(self, recording_path: str | Path, *,
                 version: dict | None = None,
                 scenario_id: str | None = None,
                 trial_index: int | None = None):
        path = Path(recording_path)
        if not path.exists():
            raise RecordingError(f"recording not found: {path}")
        try:
            data = json.loads(path.read_text())
        except json.JSONDecodeError as e:
            raise RecordingError(f"recording is not valid JSON: {path} ({e})")
        self._validate(data, version, scenario_id, trial_index)
        self.data = data
        self._step = 0

    def _validate(self, data: dict, version: dict | None,
                  scenario_id: str | None, trial_index: int | None) -> None:
        if data.get("schema_version") != SCHEMA_VERSION:
            raise RecordingError(
                f"incompatible recording schema: got {data.get('schema_version')}, "
                f"need {SCHEMA_VERSION}")
        if not data.get("turns"):
            raise RecordingError("recording has no turns (incomplete)")
        if version is not None:
            rec_fp = (data.get("version") or {}).get("fingerprint")
            if rec_fp != version.get("fingerprint"):
                raise RecordingError(
                    f"recording is for version fingerprint {rec_fp}, "
                    f"but replay requested {version.get('fingerprint')}")
        if scenario_id is not None and data.get("scenario_id") != scenario_id:
            raise RecordingError(
                f"recording is for scenario {data.get('scenario_id')}, "
                f"but replay requested {scenario_id}")
        if trial_index is not None and data.get("trial_index") != trial_index:
            raise RecordingError(
                f"recording is for trial {data.get('trial_index')}, "
                f"but replay requested {trial_index}")

    def complete(self, *, version_name: str, scenario_id: str, step: int,
                 messages: list[dict], chaos: dict) -> LLMResponse:
        # No network, no provider calls — pure replay from the recording.
        turns = self.data["turns"]
        if step >= len(turns):
            raise RecordingError(
                f"recording truncated: replay needs step {step}, "
                f"but recording has only {len(turns)} turns")
        turn = turns[step]
        resp = turn["response"]
        return LLMResponse(
            tool_calls=resp.get("tool_calls", []),
            final_answer=resp.get("content"),
            input_tokens=resp.get("input_tokens", 0),
            output_tokens=resp.get("output_tokens", 0),
            provider="replay",
            model=self.data.get("model_id", ""),
        )
