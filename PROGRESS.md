# AgentRelease Lab — Implementation Progress

Single source of truth for what is implemented, tested, and remaining.
Updated as increments land. Dates are 2026-09-29 unless noted.

## Increments (per brief §IMPLEMENTATION ORDER)

| # | Increment | Status | Verified how |
|---|-----------|--------|--------------|
| 1 | Local env, DB migrations, auth, tenant isolation | DONE | Flyway migrations run on PostgreSQL 16 + pgvector; JUnit tenant-isolation tests green |
| 2 | Service desk APIs, permission-aware retrieval, live AI agent | DONE | REST integration tests; agent worker executes real tool loop vs live backend |
| 3 | Trace capture and evaluation runner | DONE | Trace events persisted; eval runner produces metrics JSON |
| 4 | Failure injection + baseline/candidate comparisons | DONE | 10 chaos scenarios, each with expected outcome + machine-checkable assertions |
| 5 | Release gates, dashboard, CI integration | DONE | Release decision endpoint; dashboard pages render from API; GH Actions workflow |

## Implemented

- Platform (Spring Boot 3.2.x, Java 21): API-key auth, tenant isolation at query layer,
  tickets, versioned runbooks, service status, access-request API, permission-aware
  retrieval (pgvector, tenant+role filtered), tool gateway (JSON-schema arg validation,
  allowlist, timeouts, per-run action budgets, idempotency keys), approval workflow
  (fingerprint binds exact action+requester+tenant+expiry; revalidated on execution),
  agent version registry with SHA-256 config fingerprints, eval run storage, trace
  event storage, release policy + decision engine.
- Agent worker (Python 3.12, FastAPI): agent ReAct-style loop, fixture LLM
  (deterministic, labeled `fixture`), OpenAI-compatible live LLM provider
  (configurable base URL / model / key), tool calls through the platform gateway,
  Redis job queue with bounded retries + idempotent processing, OTel spans
  correlated by trace_id, evaluation metric computation (deterministic checks;
  LLM judge optional and labeled probabilistic).
- Dashboard (React 18 + TypeScript + Vite): versions, datasets, baseline-vs-candidate
  comparison, trace timeline, failure details, approval queue, release decision.
  All metrics from stored execution results; seeded demo data labeled DEMO;
  loading / empty / error states on every page.
- Eval datasets: `eval/datasets/*.json` with provenance + expected outcomes.
- Failure injection lab: 10 scenarios, each documented with expected outcome and
  assertions (`eval/scenarios/`).
- Benchmark: `benchmarks/run_eval.py` drives baseline vs candidates, writes
  machine-readable report to `benchmarks/reports/`.
- CI: `.github/workflows/ci.yml` builds/tests all three components, runs a
  fixture-mode evaluation, uploads the report artifact.
- Docs: README, ARCHITECTURE, THREAT_MODEL, LIMITATIONS, API, ADRs, dataset
  provenance, CONTRIBUTING, Apache-2.0 license.

## Tested

- Backend: `mvn test` — unit tests for tenant isolation, approval revalidation,
  idempotency, config fingerprint stability; integration tests (Testcontainers-free:
  uses local PostgreSQL) for full ticket→approval→execution workflow and
  cross-tenant denial.
- Worker: `pytest` — metric computation, fixture determinism, retry/idempotency,
  chaos scenario assertions.
- Dashboard: `npm run build` clean; pages verified against live API via screenshots.
- Benchmark reports in `benchmarks/reports/` were generated from actual runs
  (fixture mode, labeled). Runtime measurements (latency) are real measurements
  of the fixture-mode runs, not production claims.

## Remaining

- Publishing: `git remote add origin <url>` + push (requires explicit authorization;
  no push performed).
- Live-model benchmark report: requires an API key; fixture report is checked in.
- Screenshots: captured from the running dashboard (see `screenshots/`).

## Known gaps / honest limitations

See `docs/LIMITATIONS.md`. Headline items: fixture embeddings are hash-based
(labeled); dashboard demo data is synthetic (labeled DEMO); no real customer data
anywhere; latency numbers reflect a local sandbox, not production.
