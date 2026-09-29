# ADR-0006: Recorded-response replay semantics

Date: 2026-09-29 · Status: Accepted

## Context

Debugging a failed evaluation is easier when the exact model transcript can
be replayed without paying for (or waiting on) fresh inference.

## Decision

- The worker can run in `replay` mode: given a recorded transcript
  (sanitized inputs, retrieved doc versions, tool calls, tool responses),
  it re-executes the *harness* deterministically — same tool calls against
  the live gateway, same metric computation.
- Replay is documented, in UI copy and report text, as **not reproducing a
  fresh model's behavior**: a live model may choose different tools, different
  arguments, or different phrasing. Replay reproduces the *execution*, not the
  *decision-making*.
- We never request, store, or display hidden chain-of-thought. Recorded
  artifacts contain only observable events.

## Consequences

+ Reproducible debugging of harness/metric bugs without model spend.
+ Honest labeling prevents replay results from being cited as model-behavior
  evidence.
