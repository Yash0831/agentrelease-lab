# Contributing to AgentRelease Lab

## Ground rules

1. **No fabricated claims.** Never invent customers, performance improvements,
   savings, uptime, or resume metrics. Synthetic demo data must be labeled
   (`DEMO` / `synthetic`). Runtime measurements must be real measurements of
   what you actually ran, with the environment noted.
2. **Label fixture mode.** Fixture-mode LLM responses are deterministic test doubles.
   Never present them as live AI output. The `mode` field (`fixture` / `live` /
   `replay`) must be preserved end-to-end: worker → trace events → metrics →
   dashboard → reports.
3. **Security first.** Tenant isolation and the approval workflow are the
   product's core promises. Changes touching `platform` auth, retrieval
   filtering, the tool gateway, or approvals need tests proving the negative
   (cross-tenant denial, unapproved execution rejected, replayed approval
   rejected).
4. **Small, verifiable increments.** Follow `PROGRESS.md`: complete and verify
   each increment before expanding.

## Development setup

```bash
cp .env.example .env
docker compose up --build
```

The platform seeds two demo tenants, users, runbooks, and tickets on first
start (all labeled synthetic). See `README.md` for the one-command demo.

## Running tests

```bash
# Platform (Java 21 required)
cd platform && mvn test
# Worker
cd agent-worker && python -m pytest
# Dashboard
cd dashboard && npm ci && npm run build
```

## Adding a failure-injection scenario

1. Add the scenario definition to `eval/scenarios/` with: id, description,
   chaos config, **expected outcome**, and machine-checkable assertions.
2. Implement the chaos hook in the worker (`app/chaos.py`) and/or platform.
3. Add a dataset entry in `eval/datasets/` referencing it.
4. Add a test that asserts the expected outcome.

## Adding an agent version

Register via `POST /api/agent-versions` (see `docs/API.md`). A version is
immutable once evaluation runs reference it: prompt, model id, retrieval
config, tool schemas, document snapshot id, and policy version are hashed into
a SHA-256 fingerprint.

## Release process

There are no releases with version numbers; `main` is the product. The
`release-decision` CI job evaluates the candidate defined in
`.github/workflows/ci.yml` and uploads the machine-readable report.
