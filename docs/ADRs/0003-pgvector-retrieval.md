# ADR-0003: pgvector for permission-aware retrieval

Date: 2026-09-29 · Status: Accepted

## Context

The agent retrieves versioned internal runbooks. Retrieval must be
permission-aware *before* ranking: a user must never see another tenant's
documents, and role ACLs must filter within a tenant.

## Decision

- Store runbook chunk embeddings in PostgreSQL using pgvector (`vector(384)`).
- The retrieval query filters `tenant_id = :tenant AND status='CURRENT' AND
  :role = ANY(allowed_roles)` **before** the `<=>` distance ordering, so
  permission filtering cannot be bypassed by ranking.
- Embedding provider is configurable. `fixture` mode uses deterministic
  SHA-256-derived pseudo-embeddings (labeled; measures pipeline behavior, not
  semantic quality). A live deployment would point at a real embedding model.

## Consequences

+ One database for domain data, vectors, traces, and eval results — the demo
  stays one-command.
+ Pre-filtering makes the security property testable with plain SQL assertions.
- pgvector ties us to PostgreSQL (accepted: it is already the system of
  record).
