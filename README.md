# AgentRelease Lab

**Can we safely release this new version of our AI agent — and what evidence
supports that decision?**

AgentRelease Lab is a working engineering project (not a mockup) that answers
that question for AI agents which retrieve internal documents and call
business APIs. It runs a baseline and candidate agent versions against the
same failure-injection scenarios, measures safety and quality outcomes, and
renders an evidence-backed release decision.

## What it is

A realistic IT service-desk environment with two isolated organizations,
role-based users, versioned runbooks, tickets, service status, and an
access-request API — plus:

- a **real AI agent** (configurable OpenAI-compatible LLM provider, or a
  deterministic **fixture mode** for cheap CI — always labeled, never
  presented as live AI),
- a **tool gateway** that enforces allowlists, JSON-schema argument
  validation, timeouts, action budgets, and idempotency keys *independently of
  the model*,
- an **approval workflow** where sensitive access changes need a human
  reviewer, with the approval fingerprint revalidated at execution time,
- an **evaluation engine** with deterministic checks (permissions, state
  changes, citations, schema validity, duplicates) and an optional,
  labeled-probabilistic LLM judge,
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

Every number in the report comes from actual runs. Fixture-mode runs are
labeled `mode: fixture` everywhere — they measure harness behavior, not model
quality.

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

## Evidence & honesty rules

- Synthetic demo data is labeled (`synthetic`, `DEMO`); there are no
  production customers, incidents, or metrics anywhere.
- Runtime measurements (latency, cost) in checked-in reports are real
  measurements of local fixture-mode runs — not production claims.
- Recorded-response replay reproduces execution for debugging, not a fresh
  model's nondeterministic behavior.
- See `docs/LIMITATIONS.md` and `docs/THREAT_MODEL.md`.

## Publishing

The local git repository is complete and verified. To publish (requires your
explicit go-ahead — no push has been performed):
```bash
git remote add origin <your-repo-url>
git push -u origin main
```

## License

MIT — see `LICENSE`. Contributions: see `CONTRIBUTING.md`.
