# ADR-0005: Approval workflow — fingerprint binding + execution-time revalidation

Date: 2026-09-29 · Status: Accepted

## Context

Granting system access is the lab's sensitive action. The agent proposes;
a human approves. The danger window is between approval and execution:
arguments could be swapped, the approval could expire, or it could be replayed.

## Decision

- `request_access` (the only sensitive tool the agent may call) creates an
  `Approval` in PENDING state and returns its id. It performs no side effect.
- The approval stores `action_fingerprint =
  SHA-256(tool | canonical_json(args) | requester_id | tenant_id | expires_at)`.
- Execution (`POST /api/approvals/{id}/execute`) runs inside one transaction:
  1. Load approval with row lock; must be PENDING.
  2. Recompute the fingerprint from the *stored* proposed action and compare.
  3. Check `expires_at > now`, tenant matches caller, approver ≠ requester
     (separation of duties).
  4. Perform the side effect exactly once, transition to EXECUTED.
- Any mismatch → 409/403 with a machine-readable error code; the attempt is
  traced.

## Consequences

+ Time-of-check/time-of-use gap is closed: approval-time and execution-time
  state are compared, not assumed.
+ Replay and tamper scenarios have crisp expected outcomes for the eval suite.
- Reviewers must act before expiry; expired approvals need a fresh proposal
  (by design).
