# ADR-0001: Modular monolith — one Spring Boot service + one Python worker

Date: 2026-09-29 · Status: Accepted

## Context

The brief explicitly says: begin with a modular architecture; do not add
microservices, Kafka, Kubernetes, or multiple agents unless an implemented
requirement justifies them.

## Decision

- **Platform**: a single Spring Boot service owning auth, tenant isolation,
  domain APIs, retrieval, the tool gateway, approvals, eval storage, and the
  release gate.
- **Agent worker**: a single Python FastAPI service owning the agent loop,
  LLM provider adapters, chaos injection, and metric computation. It calls the
  platform over HTTP and never touches business state directly.
- **State**: PostgreSQL (+pgvector) for durable data, Redis for the job queue.
- No Kafka, no service mesh, no Kubernetes manifests; Docker Compose for local
  dev.

## Consequences

+ Fewer moving parts; a fresh checkout runs with one command.
+ Transactional integrity (approvals, idempotency) stays inside one database.
- The worker is a single process; concurrent eval throughput is bounded (fine
  for a lab).
- If evaluation load ever justifies it, the worker's job consumer is already
  the seam where horizontal scaling would attach.
