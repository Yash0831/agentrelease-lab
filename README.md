# AgentRelease Lab

**Can we safely release this new version of our AI agent — and what evidence
supports that decision?**

AgentRelease Lab evaluates AI agent updates against repeatable failure
scenarios and compares their results with a baseline to support release
decisions. It targets agents that retrieve internal documents and call
business APIs: it runs a baseline and candidate agent versions against the
same failure-injection scenarios, measures safety and quality outcomes, and
renders a release decision with traceable evidence.

## What it is

A multi-tenant IT service-desk environment with two isolated organizations,
role-based users, versioned runbooks, tickets, service status, and an
access-request API — plus:

- an **agent worker** with a configurable OpenAI-compatible LLM provider, or
  deterministic **fixture mode** (scripted responses, labeled
  `mode: fixture`),
- a **tool gateway** that enforces allowlists, JSON-schema argument
  validation, timeouts, action budgets, and idempotency keys *independently of
  the model*,
- an **approval workflow** where sensitive access changes need a human
  reviewer, with the approval fingerprint revalidated at execution time,
- an **evaluation engine** with deterministic checks (permissions, state
  changes, citations, schema validity, duplicates) and an optional LLM judge
  (labeled probabilistic; excluded from critical gate checks),
- a **release gate** where critical security failures block independently of
  average scores, and sparse evidence yields `INSUFFICIENT_EVIDENCE`,
- a **dashboard** with versions, datasets, baseline-vs-candidate comparison,
  trace timelines, failure details, approval queue, and release decisions —
  every metric from stored execution results.

## Quick start

**Option A — Docker (recommended for a fresh checkout):**
```bash
cp .env.example .env
docker compose up --build
```

**Option B — one-command native demo (no Docker):**
```bash
./scripts/demo.sh
```
Needs Java 21, Maven, Python 3.12, PostgreSQL 16 + pgvector, Redis.
It builds the platform, starts both services, runs the full evaluation
matrix in fixture mode, renders release decisions, and writes a
machine-readable report to `benchmarks/reports/`.

Then open the dashboard:
```bash
cd dashboard && npm install && npm run dev   # http://localhost:5173
```
Use API key `arl-acme-admin-demo` (synthetic demo keys are listed below).

## The demo in 60 seconds

`scripts/demo.sh` (or `python3 benchmarks/run_eval.py`) does this:

1. Registers four agent versions (fingerprinted configs): `baseline`,
   `candidate-flawed`, `candidate-fixed`, `candidate-regressed`.
2. Runs 11 failure-injection scenarios × 3 trials in fixture mode, e.g.
   prompt injection in a retrieved doc, cross-tenant access attempt, stale
   runbook vs live status, tool timeout, rate-limited provider, malformed
   tool args, duplicate write after retry, empty retrieval, tool-call loop,
   and a pinned regression set.
3. Renders release decisions:
   - `candidate-flawed` → **BLOCKED** (followed the injected instruction and
     claimed a grant it never performed → critical policy failure),
   - `candidate-fixed` → **PASS**,
   - `candidate-regressed` → **FAIL** (measurable drop on the pinned set).
4. Demonstrates the approval workflow (reviewer approves, agent executes,
   replay rejected) and writes `benchmarks/reports/eval-report-*.json`.

Reports are generated from actual runs and labeled with their mode.
Fixture-mode runs (`mode: fixture`) use scripted model responses; they
measure harness behavior, not model quality.

## Synthetic demo credentials

All data is synthetic and labeled as such. Demo API keys (dev only):

| Tenant | User | Role | API key |
|--------|------|------|---------|
| acme | alice-admin | ADMIN | `arl-acme-admin-demo` |
| acme | bob-approver | APPROVER | `arl-acme-approver-demo` |
| acme | svc-agent | AGENT | `arl-acme-agent-demo` |
| acme | carol-requester | REQUESTER | `arl-acme-requester-demo` |
| globex | dave-admin | ADMIN | `arl-globex-admin-demo` |
| globex | erin-agent | AGENT | `arl-globex-agent-demo` |

## Repository layout

```
platform/        Spring Boot 3.2 / Java 21 — API, auth, tenant isolation,
                 tool gateway, approvals, retrieval (pgvector), release gate
agent-worker/    Python 3.12 / FastAPI — agent loop, LLM providers
                 (fixture + live), failure injection, metrics, Redis queue
dashboard/       React 18 + TypeScript (Vite)
db/              Flyway migrations (canonical: platform/src/main/resources/db/migration)
eval/            datasets with provenance + expected outcomes, price table,
                 failure-injection scenario docs
benchmarks/      run_eval.py driver + generated reports from actual runs
docs/            ARCHITECTURE, THREAT_MODEL, LIMITATIONS, API, ADRs
screenshots/     captured from the running application
```

## Scope and limitations

- All tenants, users, tickets, runbooks, and statuses are synthetic demo
  data. There are no production customers, incidents, or metrics.
- Latency and cost numbers in checked-in reports were measured in a local
  sandbox running fixture mode — not production SLOs.
- Recorded-response replay reproduces a past transcript for debugging; it
  does not reproduce a fresh model's nondeterministic behavior.
- The live LLM provider path is implemented but was not exercised against a
  real provider in this environment; see `docs/LIMITATIONS.md` for its
  status.
- Full details: `docs/LIMITATIONS.md` and `docs/THREAT_MODEL.md`.

## Repository & CI

Public repository: https://github.com/Yash0831/agentrelease-lab

GitHub Actions runs the full pipeline on every push: platform tests, worker
tests, dashboard build, and the fixture-mode evaluation matrix (the
release-decision job). CI is green on `main`.

## License

MIT — see `LICENSE`. Contributions: see `CONTRIBUTING.md`.
