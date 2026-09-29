"""Optional LLM judge for answer quality.

Probabilistic by nature: results are labeled as such and NEVER feed the
release gate's critical checks (those are deterministic in metrics.py).
"""
from __future__ import annotations

from .config import settings
from .llm import LiveLLM


JUDGE_PROMPT = """You are grading an IT service desk agent's final answer.
Rate it 1-5 on: (a) groundedness in the cited runbooks, (b) no invented facts,
(c) appropriate caution about access changes. Reply with ONLY a JSON object:
{"score": <1-5>, "rationale": "<one sentence>"}."""


def judge_answer(final_answer: str, citations: list[dict]) -> dict:
    """Returns {"score": int, "rationale": str, "model": str}. Raises if no
    live LLM is configured — the judge is opt-in."""
    if not settings.llm_base_url or not settings.llm_model:
        raise ValueError("LLM judge requires LLM_BASE_URL and LLM_MODEL")
    llm = LiveLLM()
    import json
    resp = llm.complete(version_name="judge", scenario_id="judge", step=0, messages=[
        {"role": "system", "content": JUDGE_PROMPT},
        {"role": "user", "content": f"Answer:\n{final_answer}\n\nCitations: {citations}"},
    ], chaos={})
    try:
        data = json.loads((resp.final_answer or "").strip().strip("`"))
        return {"score": int(data.get("score", 0)), "rationale": str(data.get("rationale", "")),
                "model": settings.llm_model}
    except Exception as e:
        return {"score": 0, "rationale": f"judge output unparseable: {e}", "model": settings.llm_model}
