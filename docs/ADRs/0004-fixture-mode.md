# ADR-0004: Deterministic fixture LLM mode, explicitly labeled

Date: 2026-09-29 · Status: Accepted

## Context

Evaluating agents against live model providers on every CI run is expensive,
slow, and nondeterministic. But a fake model must never be mistaken for a
real one.

## Decision

- The worker supports two LLM providers behind one interface:
  - `live`: OpenAI-compatible chat-completions endpoint (configurable base
    URL, model, key, timeout).
  - `fixture`: deterministic, scripted responses selected by
    (scenario_id, step, attempt). No network calls, no tokens, zero cost.
- Every run, trace event, metric set, dashboard view, and report carries a
  mandatory `mode` field: `fixture` | `live` | `replay`.
- Fixture responses are **never** presented as live AI: dashboard badges,
  report headers, and log lines all show the mode.

## Consequences

+ CI runs the full evaluation matrix in seconds for free.
+ Fixture determinism makes failure-injection scenarios reproducible.
- Fixture results measure harness + scenario behavior, not model quality;
  release decisions from fixture runs are labeled accordingly.
