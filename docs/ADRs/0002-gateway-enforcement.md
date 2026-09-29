# ADR-0002: Policy enforcement in the platform gateway, never in the prompt

Date: 2026-09-29 · Status: Accepted

## Context

The agent under test may be prompt-injected, buggy, or adversarial. If safety
depended on the model "following instructions", every evaluation would be
testing the model's mood rather than the system's guarantees.

## Decision

All security-relevant enforcement lives in the Spring Boot tool gateway and
retrieval layer, which the model cannot reach:

- Tenant id comes from the authenticated API key, never from model output.
- Retrieval SQL always filters `tenant_id` (+ role ACLs).
- Tool arguments are validated against JSON schemas in Java.
- Sensitive tools are absent from the agent's allowlist; the agent can only
  *propose* via `request_access`, and execution requires a human approval
  that is revalidated (fingerprint over tool + canonical args + requester +
  tenant + expiry) at execution time.
- Per-run action budgets and per-call timeouts bound runaway behavior.

## Consequences

+ Prompt-injection and cross-tenant scenarios become *negative tests with teeth*:
  the expected outcome is "blocked by the gateway", which we can assert.
+ The lab measures what the *system* guarantees, not what the model promises.
- The gateway must stay in sync with tool schemas (mitigated: schemas are
  versioned with the agent config and fingerprinted).
