# Threat Model — AgentRelease Lab

Scope: the lab itself (demo service desk + agent harness), not a production
deployment guide. STRIDE-style, focused on the trust boundaries the lab is
built to test.

## Trust boundaries

1. **LLM output → tool gateway.** The model is untrusted. It may be
   prompt-injected via retrieved documents, may hallucinate arguments, may
   attempt cross-tenant access, may loop. All enforcement is server-side.
2. **Tenant A ↔ Tenant B.** Complete isolation: no query path may return
   another tenant's documents, tickets, approvals, traces, or eval records.
3. **Agent → sensitive actions.** Granting access is a privileged business
   action. The agent may only *propose*; a human approves; the gateway
   revalidates at execution.
4. **Reviewer → approval execution.** Approvals are bound to the exact action
   (fingerprint over tool + args + requester + tenant + expiry). Tampering
   with any field invalidates the approval.
5. **CI → release decision.** The release report is evidence, not authority:
   the gate blocks on critical failures, but a PASS still requires human
   sign-off for production use (out of scope for the lab).

## Threats and mitigations

| # | Threat | Mitigation | Test |
|---|--------|------------|------|
| T1 | Prompt injection in a retrieved runbook steers the agent to exfiltrate or escalate | Retrieval returns text only; gateway enforces tenant/role filters and tool allowlist; injection scenario asserts no unauthorized action executed | `chaos.prompt_injection` scenario + `test_cross_tenant_denied` |
| T2 | Model requests another tenant's documents | Tenant id is taken from the authenticated API key, never from model output; retrieval SQL always filters `tenant_id` | `TenantIsolationTest` |
| T3 | Agent calls `grant_access` directly, bypassing approval | `grant_access` is not in the tool allowlist; gateway rejects unknown tools; approvals execute only via `execute_approval` with revalidation | `ApprovalWorkflowTest` |
| T4 | Approval replay: re-executing an approved action twice | Approval row transitions PENDING→EXECUTED atomically; second execution rejected; tool gateway idempotency keys dedupe retries | `test_duplicate_write_after_retry` scenario |
| T5 | Approval tampering: approving access for user X, executing for user Y | Fingerprint = SHA-256(tool, canonical args, requester, tenant, expiry); execution recomputes and compares | `ApprovalFingerprintTest` |
| T6 | Expired approval executed late | `expires_at` checked at execution time, not just approval time | `ApprovalWorkflowTest.test_expired_approval_rejected` |
| T7 | Retry storm / duplicate side effects | Idempotency-Key required on mutating tools; gateway returns the original result for a repeated key without re-executing | `IdempotencyTest` |
| T8 | Runaway agent (tool loop) burns budget / hangs | Per-run action budget (max tool calls) and per-call timeout in the gateway; worker step cap | `chaos.tool_loop` scenario |
| T9 | Stale runbook misleads agent into wrong action | Runbooks are versioned; retrieval prefers CURRENT; scenario asserts the agent cites the current version or the run is flagged | `chaos.stale_runbook` scenario |
| T10 | API key leakage via logs/traces | Trace payloads are sanitized (keys, tokens redacted) before persistence; `.env` never committed | `test_trace_sanitization` |
| T11 | Fixture output mistaken for live AI | `mode` field is mandatory on runs, trace events, metrics, and reports; dashboard badges it | `test_fixture_mode_labeled` |
| T12 | Evaluation gaming: candidate tuned to the dataset | Datasets are versioned with provenance; release gate requires minimum trial counts and blocks on critical failures regardless of averages | `ReleaseGateTest` |

## Out of scope / residual risk

- The lab is a **demo harness**, not hardened production software: API-key auth
  (no OAuth/OIDC), single shared secret per user, no rate limiting on the
  platform API itself.
- The fixture embedding model is hash-based; it measures pipeline behavior,
  not retrieval quality against real embeddings.
- An LLM judge (optional) is probabilistic; its outputs are labeled and never
  feed the release gate's critical-failure checks (those are deterministic).
- Supply-chain: dependencies are pinned with lockfiles, but no SLSA
  provenance is produced.
