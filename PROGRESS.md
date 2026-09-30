# AgentRelease Lab — Implementation Progress

Single source of truth for what is implemented, tested, and remaining.
Updated as increments land. Dates are 2026-09-29 unless noted.

## Increments (per brief §IMPLEMENTATION ORDER)

| # | Increment | Status | Verified how |
|---|-----------|--------|--------------|
| 1 | Local env, DB migrations, auth, tenant isolation | DONE | Flyway migrations on PostgreSQL 16 + pgvector; 30/30 JUnit tests green |
| 2 | Service desk APIs, permission-aware retrieval, agent worker | DONE | JUnit + pytest; worker executes fixture tool loop vs live backend |
| 3 | Trace capture and evaluation runner | DONE | Trace events persisted; benchmark writes metrics report JSON |
| 4 | Failure injection + baseline/candidate comparisons | DONE | Chaos scenarios exercised in benchmark; flawed → BLOCKED, regressed → FAIL |
| 5 | Release gates, dashboard, CI integration | DONE | Release decision endpoint verified via benchmark; dashboard screenshots captured against the live API; CI ran green on a GitHub-hosted runner (platform, worker, dashboard, release-decision jobs) |

## Implemented

- Platform (Spring Boot 3.2.x, Java 21): API-key auth, tenant isolation at query layer,
  tickets, versioned runbooks, service status, access-request API, permission-aware
  retrieval (pgvector, tenant+role filtered), tool gateway (JSON-schema arg validation,
  allowlist, timeouts, per-run action budgets, idempotency keys, prompt-injection
  tripwire → INJECTION_BLOCKED), approval workflow (fingerprint binds exact
  action+requester+tenant+expiry; revalidated on execution under a row lock),
  agent version registry with SHA-256 config fingerprints, eval run storage, trace
  event storage, release policy + decision engine.
- Agent worker (Python 3.12, FastAPI): agent ReAct-style loop, fixture LLM
  (deterministic, labeled `fixture`), OpenAI-compatible live LLM provider
  (configurable base URL / model / key — wired but never exercised against a real
  model in this environment), tool calls through the platform gateway,
  Redis job queue with bounded retries + idempotent processing, OTel spans
  correlated by trace_id, evaluation metric computation (deterministic checks;
  LLM judge optional and labeled probabilistic).
- Dashboard (React 18 + TypeScript + Vite): versions, datasets, baseline-vs-candidate
  comparison, trace timeline, failure details, approval queue, release decision.
  `npm run build` clean. Rendering verified against the live API via headless
  Chromium; screenshots in `screenshots/` show real data.
- Eval datasets: `eval/datasets/*.json` with provenance + expected outcomes.
- Failure injection lab: chaos scenarios documented in `eval/scenarios/`.
- Benchmark: `benchmarks/run_eval.py` drives baseline vs candidates, writes
  machine-readable report to `benchmarks/reports/`.
- CI: `.github/workflows/ci.yml` ran green on a GitHub-hosted runner 2026-09-29
  (platform tests, worker tests, dashboard build, fixture-mode evaluation).
- Docs: README, ARCHITECTURE, THREAT_MODEL, LIMITATIONS, API, ADRs, dataset
  provenance, CONTRIBUTING.

## Tested (2026-09-29, sandbox)

- Backend: `mvn -o test` — **30/30 green** (CorsPreflightTest 2, ApprovalWorkflowTest 5,
  FingerprintTest 4, TenantIsolationTest 6, GatewayTest 8, ReleaseGateTest 4,
  ApprovalRoundTripTest 1).
  Includes the non-transactional fingerprint round-trip regression test and
  CORS preflight tests (OPTIONS bypasses auth; GET without key still 401).
- Worker: `pytest` — **16/16 green**.
- Dashboard: `npm run build` clean; 48 modules transformed. Rendered live via
  headless Chromium (CDP Fetch-domain proxying — see scripts/capture_screenshots.py);
  screenshots in `screenshots/` show real data on Overview/Versions/Compare/Decisions.
- Benchmark (fixture mode, labeled on every run/metric/report): **6/6 assertions** —
  flawed → BLOCKED (critical policy failure cited with concrete counts), fixed → PASS,
  regressed → FAIL, approval executed exactly once with replay rejected.
  Report: `benchmarks/reports/eval-report-fixture-20260929-165151.json`.
- Live end-to-end: platform :8080, worker :8001, dashboard :5173 all
  running; seed data (Acme/Globex tenants, demo users incl. dave-newhire) loads.
- CORS: platform answers preflights (`Access-Control-Allow-Origin: *`, configurable
  via ARL_CORS_ALLOWED_ORIGINS); auth filter skips OPTIONS. Verified live.

## Bugs found and fixed during testing

- Approval action fingerprint included `expires_at` with nanosecond precision, but
  PostgreSQL `timestamptz` stores microseconds — every approval looked tampered
  after a DB round-trip (`APPROVAL_TAMPERED` on execute). Unit tests missed it
  because propose→execute ran inside one transaction. Fix: truncate expiry to
  microseconds before fingerprinting; added `executeSurvivesDatabaseRoundTrip`
  regression test (flush + clear between propose and execute).
- Spring Data found zero repositories when declared as nested interfaces —
  extracted to 13 top-level repository files.
- Eval runs/traces/decisions were not tenant-scoped — added tenant ownership +
  cross-tenant denial tests and migration V3.
- Approval approve/reject/execute now use `PESSIMISTIC_WRITE` row locks.
- Tool gateway blocks prompt-injection markers as INJECTION_BLOCKED before
  business logic (persisted for audit).
- Benchmark re-runs collided on the eval-run uniqueness key — clean single-process
  runs only; a batch/run identifier is still needed for repeatability.

## Remaining

- Docker/Compose: untested (platform dataset path, worker price-table path,
  dashboard Vite build args, root `db/` deliverable).
- True concurrency test for approval execution (row lock is wired, race test missing).
- Idempotency-key mismatch conflict (same key + different args → stable conflict).
- Live-LLM path: wired but never exercised against a real model (also needs the
  ```tool_call convention added to the agent system prompt — see agent-worker/app/agent.py).

## Known gaps / honest limitations

See `docs/LIMITATIONS.md`. Headline items: fixture embeddings are hash-based
(labeled); live-LLM path never ran against a real model here; dashboard demo data
is synthetic (labeled DEMO); no real customer data anywhere; latency numbers
reflect a local sandbox, not production.
