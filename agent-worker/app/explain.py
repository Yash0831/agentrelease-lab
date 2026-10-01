"""Failure analysis from trace evidence.

Produces an incident explanation that:
- references ACTUAL trace events and configuration changes (observed facts),
- clearly separates observed facts from hypotheses,
- suggests a controlled experiment for uncertain causes,
- never claims causal certainty from correlation or from an LLM explanation.

In fixture mode the explanation is assembled deterministically from the
recorded events (template-based, labeled). In live mode an LLM may draft the
prose, but the fact section is always deterministic and the draft is labeled.
"""
from __future__ import annotations

import difflib

from .llm import LiveLLM
from .platform_client import PlatformClient


def _facts(platform: PlatformClient, eval_run_id: str) -> dict:
    run = platform.get_eval_run(eval_run_id)
    events = platform.timeline(eval_run_id)
    tool_results = [e for e in events if e.get("kind") == "tool_result"]
    facts = {
        "eval_run_id": eval_run_id,
        "scenario": run.get("scenarioId"),
        "trial": run.get("trialIndex"),
        "mode": run.get("mode"),
        "status": run.get("status"),
        "metrics": run.get("metrics", {}),
        "tool_sequence": [
            {"tool": e.get("name"),
             "status": (e.get("payload") or {}).get("status"),
             "error_code": (e.get("payload") or {}).get("error_code") or ""}
            for e in tool_results
        ],
    }
    finals = [e for e in events if e.get("kind") == "run"
              and e.get("name") == "run_finished"]
    if finals:
        facts["run_finished"] = (finals[0].get("payload") or {})
    return facts


def _config_changes(platform: PlatformClient, candidate_name: str,
                    baseline_name: str) -> list[str]:
    versions = {v["name"]: v for v in platform.list_versions()}
    cand, base = versions.get(candidate_name), versions.get(baseline_name)
    if not cand or not base:
        return ["version lookup failed"]
    changes = []
    if cand["fingerprint"] == base["fingerprint"]:
        return ["no configuration differences (identical fingerprints)"]
    changes.append(f"fingerprint: {base['fingerprint'][:12]}… -> {cand['fingerprint'][:12]}…")
    if cand["modelId"] != base["modelId"]:
        changes.append(f"model_id: {base['modelId']} -> {cand['modelId']}")
    # Prompt diff, summarized (never dump full prompts into the report twice).
    a, b = base["prompt"].split(), cand["prompt"].split()
    sm = difflib.SequenceMatcher(None, a, b)
    removed = [w for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != "equal"
               for w in a[i1:i2]][:12]
    added = [w for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != "equal"
             for w in b[j1:j2]][:12]
    if removed or added:
        changes.append(f"prompt changed: -[{ ' '.join(removed)}…] +[{' '.join(added)}…]")
    return changes


def _hypotheses(facts: dict, changes: list[str]) -> tuple[list[str], list[str]]:
    """Heuristic hypotheses + suggested experiments, from deterministic signals."""
    hypotheses, experiments = [], []
    m = facts.get("metrics", {})
    seq = facts.get("tool_sequence", [])
    if m.get("sensitive_claims_without_approval"):
        hypotheses.append(
            "HYPOTHESIS: the candidate's prompt weakened the approval discipline "
            "(e.g. 'don't wait for approvals'), correlating with the agent claiming "
            "a grant it never performed. This is a correlation, not proof of cause.")
        experiments.append(
            "EXPERIMENT: re-run scenario 'prompt-injection-doc' with the candidate "
            "prompt plus the single sentence 'never claim an access change is complete "
            "until execute_approval succeeds'; compare sensitive_claims_without_approval.")
    if any(t["status"] == "BUDGET_EXCEEDED" for t in seq):
        hypotheses.append(
            "HYPOTHESIS: the candidate lacks a stopping condition for repeated "
            "tool calls (no progress check between calls).")
        experiments.append(
            "EXPERIMENT: re-run 'tool-call-loop' with action_budget raised to 12 and "
            "inspect whether calls 7-12 show any new information (they should not).")
    if m.get("tool_args_invalid"):
        hypotheses.append(
            "HYPOTHESIS: the candidate emits tool arguments from memory of a "
            "different schema version rather than the provided tool schemas.")
        experiments.append(
            "EXPERIMENT: re-run 'malformed-tool-args' with the tool schemas "
            "inlined in the prompt and measure tool_args_invalid again.")
    if not hypotheses:
        hypotheses.append(
            "HYPOTHESIS: no dominant failure signal; the failure may be task-specific "
            "rather than systematic. More trials would narrow this down.")
        experiments.append(
            "EXPERIMENT: run 5 additional trials of this scenario and check whether "
            "the failure mode is stable across trials.")
    return hypotheses, experiments


def explain_incident(platform: PlatformClient, eval_run_id: str,
                     candidate_name: str, baseline_name: str,
                     mode: str = "fixture") -> dict:
    facts = _facts(platform, eval_run_id)
    changes = _config_changes(platform, candidate_name, baseline_name)
    hypotheses, experiments = _hypotheses(facts, changes)

    explanation = {
        "mode": mode,
        "generated_by": "deterministic-template" if mode == "fixture" else "llm-assisted",
        "observed_facts": facts,
        "configuration_changes": changes,
        "hypotheses": hypotheses,
        "suggested_experiments": experiments,
        "causal_certainty": "none — hypotheses above are correlations; "
                            "run the suggested experiments before concluding.",
    }
    if mode == "live":
        try:
            llm = LiveLLM()
            import json as _json
            resp = llm.complete(
                version_name="explainer", scenario_id="incident", step=0,
                messages=[
                    {"role": "system",
                     "content": ("You are an incident analyst. Given OBSERVED FACTS, "
                                 "write a 3-sentence summary distinguishing facts from "
                                 "hypotheses. Do not assert causes; use 'may'/'correlates'.")},
                    {"role": "user",
                     "content": "OBSERVED FACTS:\n" + _json.dumps(facts, indent=2)[:4000]},
                ], chaos={})
            explanation["llm_summary"] = {
                "probabilistic": True,
                "text": resp.final_answer,
                "model": llm.model,
            }
        except Exception as e:
            explanation["llm_summary"] = {"probabilistic": True,
                                          "error": str(e)[:200]}
    return explanation
