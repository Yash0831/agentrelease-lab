# ADR-0006: Recorded-response replay semantics

Date: 2026-09-29 · Status: Accepted · Amended 2026-10-01

> **Amendment (2026-10-01):** the decision below originally described replay
> as re-executing tool calls against the live gateway. That behavior was
> changed: replay now plays back **recorded** tool results and makes no
> platform calls and no business mutations. The transcript format also grew a
> schema version (now 2) that preserves citations, tool status, error codes,
> and sanitized error messages. Older recordings are rejected explicitly
> rather than replayed with missing evidence.

## Context

Debugging a failed evaluation is easier when the exact model transcript can
be replayed without paying for (or waiting on) fresh inference.

## Decision

- The worker can run in `replay` mode: given a recorded transcript
  (sanitized inputs, retrieved doc versions, tool calls, recorded tool
  results), it re-executes the *harness* deterministically. Recorded tool
  results are played back from the transcript — tools are NOT re-executed
  against the gateway, so replay causes no business mutations.
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
