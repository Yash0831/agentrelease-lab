# Controlled pilot — setup, operation, and acceptance

This is the runbook for a controlled company pilot of AgentRelease Lab.
Plain statement: **all testing to date uses synthetic representative data.
Synthetic testing is not a completed company pilot.** A pilot is complete
only when the acceptance checklist below is executed against an authorized
pilot dataset, in the pilot environment, by the pilot participants.

## 1. Pilot setup

1. Provision the pilot host (single node is the supported topology; see
   `docs/DEPLOYMENT.md` for the network diagram).
2. Install Docker and the Compose v2 plugin.
3. Create secrets in the secret manager
   (`POSTGRES_PASSWORD`, `SEED_ADMIN_API_KEY`, `WORKER_API_KEY`,
   `LLM_API_KEY` if live mode is in scope, OIDC client secrets), then
   generate `.env.deploy` at deploy time (procedure in
   `docs/DEPLOYMENT.md#secret-manager-integration`). Never commit it.
4. Configure the OIDC provider (`ARL_OIDC_ISSUER`, `ARL_OIDC_AUDIENCE`,
   `ARL_OIDC_JWKS_URI`) and the allowed CORS origins
   (`ARL_CORS_ALLOWED_ORIGINS`).
5. Start the stack:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.deploy.yml \
       --env-file .env.deploy up --build -d
   ```
6. With monitoring:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.deploy.yml \
       --env-file .env.deploy --profile monitoring up --build -d
   ```
7. Verify: `bash scripts/docker_e2e.sh` (without `--down`) against the
   pilot host — all checks must pass before onboarding users.
8. Take a baseline backup: `bash scripts/backup.sh --out /srv/arl/backups`.

## 2. Onboarding

- Participants get OIDC accounts in the pilot tenant(s). No demo API keys
  are issued — they are disabled in deployment mode.
- Roles: requesters submit evaluations; approvers approve sensitive
  actions; admins manage release policies. Least privilege by default.
- Each participant runs the dashboard against the pilot URL, submits one
  fixture-mode evaluation, and confirms they can see its batch, traces,
  and decision. Support confirms the evidence is stored under the correct
  tenant.
- The pilot dataset: until an **authorized pilot dataset** is provided,
  use the synthetic `eval/datasets/` only. Results on synthetic data
  validate the harness, not production readiness.

## 3. Operating instructions

- Daily: check Prometheus alerts (none firing), confirm the last backup
  completed (`ls /srv/arl/backups`), glance at queue depth.
- Evaluations: submit via the dashboard or `benchmarks/run_eval.py`.
  Fixture mode is the default; live mode requires explicit `--mode live`
  and configured provider credentials, and its evidence is stored
  separately from fixture evidence (never compare live verdicts against
  scripted fixture expectations).
- Cost: watch the `CostBudgetExceeded` alert; estimates come from
  `eval/price-table.json` and are not provider billing.
- Changes: image updates go through CI (all five jobs green, including
  `docker-e2e`) before `up --build -d` on the pilot host.

## 4. Support procedures

- Something failing: start with `docs/OPERATIONS.md` (alert first
  responses), then the error envelope `code` + `correlationId` in the
  platform logs.
- Lost/corrupt data: `docs/OPERATIONS.md#backup-and-restore`.
- Suspected security issue (token leak, unauthorized access): rotate the
  affected credential immediately
  (`docs/DEPLOYMENT.md#credential-rotation-procedure`), preserve logs,
  and notify the pilot owner before further investigation.
- Provider outage: pause live-mode submissions; fixture mode is unaffected.

## 5. Rollback steps

1. Identify the last known-good image (previous CI-green commit).
2. `docker compose -f docker-compose.yml -f docker-compose.deploy.yml --env-file .env.deploy up -d`
   with the image tags pinned to that commit (or `git checkout <sha>` and
   `up --build -d`).
3. Verify `/api/health`, one fixture evaluation, and one release decision.
4. If the database migrated forward incompatibly, restore the pre-deploy
   backup into a new database and point `DATABASE_URL` at it
   (`scripts/restore.sh`); keep the old database until the pilot owner
   confirms.
5. Record the rollback (time, reason, versions) in the pilot log.

## 6. Backup and recovery drill

1. `bash scripts/backup.sh --out /srv/arl/backups` — record wall-clock time.
2. On a separate clean host (or a second Compose project), restore:
   `bash scripts/restore.sh /srv/arl/backups/<ts> agentreleaselab_restore`.
3. Compare the verification counts printed by `restore.sh` (tenants,
   agent versions, eval batches, eval runs, trace events, approvals,
   release decisions) against the backup manifest.
4. Start the platform against the restored database and fetch one release
   decision and one eval run via the API.
5. Record measured recovery time and any data loss. Proposed RTO 1 hour /
   proposed RPO 24 hours are **targets**, not results — the drill produces
   the observed numbers.

Observed drill (2026-10-13, sandbox, local processes not containers):
backup of the live database (2 tenants, 4 agent versions, 220 eval runs,
2,284 trace events, 1 approval, 6 release decisions) restored into a
separate database on the same host; verification counts identical to the
source and zero committed data lost; restore step completed in ~1 second
at this dataset size. These are observations at sandbox scale, not the
proposed targets above and not a substitute for the pilot-host drill.

## 7. Acceptance checklist

Two columns: what automated evidence already demonstrates, and what only
an actual pilot can demonstrate. "Verified" below means: implemented and
demonstrated by a runnable check (CI job, script, or test suite).

| # | Criterion | Verified by automated evidence | Requires actual pilot |
|---|---|---|---|
| 1 | Task completion and evidence quality (fixture): flawed→BLOCKED, fixed→PASS, regressed→FAIL with citations and traces | Yes — `docker-e2e` CI job runs the matrix twice per push; reports uploaded as artifacts | Live-model evidence quality (a probabilistic model is never required to reproduce scripted verdicts) |
| 2 | Repeatable batches: re-runs don't collide; historical batches retrievable | Yes — `scripts/docker_e2e.sh` asserts distinct batch ids and API retrieval of both batches | — |
| 3 | Tenant isolation: cross-tenant reads/writes denied | Yes — platform tenant-isolation tests (unit) | Enforcement under real OIDC tokens and real tenant claims |
| 4 | Approval enforcement: execute requires APPROVER/ADMIN identity; fingerprint revalidated | Yes — approval workflow + round-trip tests (unit) | Execution by real approvers under OIDC; segregation-of-duties in practice |
| 5 | OIDC validation: issuer, audience, signature, expiry, permissions | Partial — local-IdP configuration documented (`docs/DEPLOYMENT.md`); integration tests are a platform/worker workstream | Validation against the corporate IdP with real tokens |
| 6 | Latency budget | Partial — `load/load_test.py` defines the proposed workload; no pilot measurements yet | Measured p50/p95/p99 on pilot hardware against agreed budgets |
| 7 | Cost budget | Partial — token usage and estimated cost tracked per run; `CostBudgetExceeded` alert defined | Actual spend against the pilot budget with the real provider |
| 8 | Failure handling: timeouts, retries, abandoned-job recovery, no duplicate mutations | Yes — worker queue tests (unit); recovery behavior documented | Injected failures in the pilot (kill -9 worker mid-job, provider 429s) producing the alerted signals |
| 9 | Observability: health, readiness, metrics, traces, alerts | Partial — configs and runbooks exist; service metrics endpoints are an integration point (see `monitoring/prometheus.yml`) | Alerts firing on real incidents with the documented first responses |
| 10 | Backup and restore: evidence survives restore into a clean environment | Partial — `scripts/backup.sh`/`restore.sh` with checksum + count verification | A real drill on pilot infrastructure with measured RTO/RPO |
| 11 | Deployment topology: private DB/Redis, TLS at proxy, no-new-privileges, read-only roots | Partial — `docker-compose.deploy.yml` is config-reviewed, not runtime-verified here | Validated by running the overlay in the pilot environment |
| 12 | Docker runtime end to end | In CI — `docker-e2e` job on GitHub runners (this sandbox has no Docker daemon) | Same stack on the pilot host |

## 8. Pilot exit criteria

The pilot is complete when: all "requires actual pilot" items above are
executed and recorded; the backup drill has observed (not proposed) RTO/RPO;
no critical alert is unactioned; and the pilot owner signs off on the
checklist. Until then, AgentRelease Lab is **not** production-ready and
must not be presented as such.
