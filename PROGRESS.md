# AgentRelease Lab — Implementation Progress

Single source of truth for what is implemented, tested, and remaining.
Updated as increments land. Dates are 2026-09-29 unless noted.

## Increments

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
  (platform tests, worker tests, dashboard build, fixture-mode evaluation);
  re-verified green on every subsequent push (platform, worker, dashboard,
  release-decision jobs).
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

## Audit increments (2026-09-30)

Full implement-and-verify audit of the eight workstreams. All items below are
implemented, tested, and committed.

| # | Increment | Status | Verified how |
|---|-----------|--------|--------------|
| A1 | Live AI path: native tool calling | DONE | Provider-native OpenAI `tools` with JSON Schemas for all six tools; assistant `tool_calls` parsed with call IDs; proper `role=tool` messages; `LLM_MODEL` from config (not hardcoded); `validate_live_config()`; native tool-call/config unit tests. **Live provider never executed here** — no `LLM_BASE_URL`/`LLM_MODEL`/`LLM_API_KEY` configured in this environment. |
| A2 | Outcome-proving evaluations | DONE | `FixtureResetService` + `POST /api/eval-runs/fixtures/reset` resets mutable fixtures (tickets, approvals, grants, service status) between trials; cross-tenant checker requires an actual `GLBX-*` access attempt and verifies denial; duplicate-write probe verifies persisted ToolCall state; separate `citations_valid`/`citations_supported`; `evidence_complete` with gap reporting; regression tests for no-attempt, denied, leaked, unsupported citations, evaluator failures. |
| A3 | Fail-closed release gate | DONE | Rejects empty required-scenario policies; validates thresholds; missing metrics/incomplete evidence → `INSUFFICIENT_EVIDENCE`; scans critical failures across all candidate runs in batch (including non-required scenarios); unrounded comparisons with rounded display; configurable baseline-regression rule with absolute/relative failure lists. Tests for empty policy, invalid thresholds, optional-scenario criticals, incomplete evidence, baseline regression. |
| A4 | Repeatable evaluation batches | DONE | `batch_id` on eval runs and release decisions (V5 migration); batch-scoped uniqueness; release evaluation requires `batchId`; worker/benchmark generate and propagate batch IDs; dashboard compare/decision pages show batch IDs. Batch-isolation/repeatability tests. **Runtime fix**: `JobRequest` now carries `batch_id` through `/jobs/run-sync` (Pydantic was dropping it, minting a fresh batch per job). |
| A5 | Docker packaging/readiness | DONE | Platform + worker Dockerfiles use repo-root context; datasets and price table copied in; `EVAL_DATASETS_DIR`/`ARL_PRICE_TABLE` set; curl installed; `/api/health` and `/health` healthchecks; compose uses build args for `VITE_PLATFORM_URL`; worker/dashboard wait for healthy platform. **Runtime NOT verified here** — no Docker daemon in this sandbox. |
| A6 | Recorded-response replay | DONE | `TurnRecorder` writes JSON recordings bound to version fingerprint/scenario/trial; `ReplayLLM` reads without provider networking; rejects missing/invalid/incompatible/truncated recordings; networking-disabled tests verify no HTTP provider call. Tests: `agent-worker/tests/test_replay.py`. |
| A7 | Idempotency + job hardening | DONE | Same idempotency key + different tool/args → auditable `CONFLICT` with `IDEMPOTENCY_KEY_CONFLICT` (V6 migration); `ConcurrencyTest.java`; atomic Lua enqueue dedup; processing leases; abandoned-job recovery; heartbeats; per-trial completion records; worker skips completed trials. Queue tests for atomic enqueue, abandoned recovery, trial completion. |
| A8 | Runtime fixes (benchmark) | DONE | `JobRequest.batch_id` propagation (see A4); `FixtureResetService` breaks `tool_calls → approvals` FK before deleting approvals; approval demo creates its own request when no pending approval survives fixture resets. **Benchmark 6/6**: flawed → BLOCKED, fixed → PASS, regressed → FAIL, approval exactly-once. Latest committed report: `benchmarks/reports/eval-report-fixture-20261006-133321.json`. |

## Follow-up fixes (2026-10-01)

| # | Increment | Status | Verified how |
|---|-----------|--------|--------------|
| F1 | Replay evidence preservation | DONE | Schema-2 recordings preserve model citations, per-tool status, error codes, and sanitized error messages end to end (gateway → recording → replay). Schema-1 recordings are rejected explicitly instead of replaying with fabricated empty evidence. Tests: citation round-trip, error-field playback, legacy-schema rejection, error-message sanitization, full AgentRunner record→replay round trip with networking disabled (no live model calls, no platform tool calls). |
| F2 | Strict release-gate input validation | DONE | `evidenceProblem` validates every finished run: mandatory metrics present and non-null, booleans are actual booleans, numerics are finite and nonnegative, recognized terminal status, evidence marked complete. Thresholds must be finite numbers of the right shape (integers where integral); wrong types and NaN are rejected as `INVALID_THRESHOLD`. No zero-substitution anywhere. Tests: DB-backed null/wrong-type/negative/non-terminal/invalid-baseline cases → `INSUFFICIENT_EVIDENCE`; direct validator tests for NaN/Infinity metrics and thresholds; valid runs still decide PASS/BLOCKED/FAIL. |
| F3 | Documentation reconciliation | DONE | Removed stale "Remaining" entries (concurrency tests now exist); corrected test counts, class names, and benchmark report links; verification status distinguishes locally verified / CI-verified / skipped / unverified. |
| F4 | Redis tests in CI | DONE | Worker CI job gains a Redis 7.4 service with health check plus an explicit readiness step that fails the job clearly if Redis never comes up. `ARL_REQUIRE_REDIS=true` makes the 4 Redis queue tests fail loudly instead of skipping when Redis is unavailable (verified locally: 4 failed against a dead port; 56/56 pass with Redis up). |
| F5 | Durable benchmark evidence | DONE | Fresh fixture report generated from current code (6/6 assertions, batch `bench-fixture-20261006-133039-4a5f87`), sanitized (synthetic data only, no credentials), and committed as `benchmarks/reports/eval-report-fixture-20261006-133321.json` (force-added; routine reports stay gitignored). PROGRESS.md links the committed file; the `release-decision` CI job also uploads each run's report as the `eval-report` artifact. |

## Tested (2026-10-06, sandbox)

Code: working tree at remote commit `b11ab5d` plus the Redis-CI and report
changes committed below. Environment: fresh VM — Java 21.0.12.1, Maven 3.9.9,
PostgreSQL 16 + pgvector, Redis 7.x, rebuilt `/root/.m2` from scratch.

- Backend: `mvn -o test` — **66/66 green**, 0 failed, 0 skipped.
  (ReleaseGateTest 19, ReleaseGateValidationTest 17, ConcurrencyTest 4,
  GatewayTest 8, TenantIsolationTest 6, ApprovalWorkflowTest 5,
  FingerprintTest 4, CorsPreflightTest 2, ApprovalRoundTripTest 1.)
- Worker: `pytest` — **56/56 green**, 0 failed, 0 skipped, with
  `ARL_REQUIRE_REDIS=true`. Fail-fast verified separately: with Redis
  pointed at a dead port and the flag set, the 4 Redis queue tests **fail**
  (4 failed, 2 passed) instead of skipping.
- Dashboard: `npm run build` clean.
- Benchmark (fixture mode, labeled): **6/6 assertions** — flawed → BLOCKED
  (3 critical policy failures), fixed → PASS, regressed → FAIL, approval
  executed once with replay rejected. Batch
  `bench-fixture-20261006-133039-4a5f87`.
- Benchmark evidence (committed): `benchmarks/reports/eval-report-fixture-20261006-133321.json`
  — generated from the current code on 2026-10-06, sanitized (no credentials;
  synthetic fixture data only), force-added. Routine `benchmarks/reports/*.json`
  output stays gitignored; this file is the durable evidence link.
  The `release-decision` CI job also uploads each run's report as the
  `eval-report` GitHub Actions artifact.

## Verification records: local vs CI

| Check | Local (2026-10-06 sandbox) | GitHub CI |
|---|---|---|
| Backend tests | 66 passed, 0 failed, 0 skipped (Java 21, PG16+pgvector) | platform job green on `b11ab5d` |
| Worker tests | 56 passed, 0 failed, 0 skipped (Redis available, `ARL_REQUIRE_REDIS=true`) | worker job on `b11ab5d`: 52 passed, **4 skipped** (no Redis service in that job) → fixed by adding a Redis service + `ARL_REQUIRE_REDIS=true` so required Redis tests fail loudly instead of skipping |
| Dashboard build | clean (`npm run build`) | dashboard job green on `b11ab5d` |
| Fixture eval | 6/6 assertions; committed report (see above) | release-decision job green on `b11ab5d`; report uploaded as `eval-report` artifact |
| Docker Compose runtime | **NOT verified** — no Docker daemon in this sandbox | not run in CI |
| Live model path | **NOT verified** — no `LLM_BASE_URL`/`LLM_MODEL`/`LLM_API_KEY` in this environment | not run in CI (documented in workflow header) |

## Known gaps / honest limitations

See `docs/LIMITATIONS.md`. Headline items: fixture embeddings are hash-based
(labeled); live-LLM path never ran against a real model here; dashboard demo data
is synthetic (labeled DEMO); no real customer data anywhere; latency numbers
reflect a local sandbox, not production.
