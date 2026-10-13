# Operations — running the pilot deployment

How to respond to alerts, how Redis and the job queue behave across
restarts, and how backup/restore works. Companion to
`docs/DEPLOYMENT.md` (topology) and `docs/PILOT.md` (pilot program).

## Alerts — meaning and first response

Alert rules live in `monitoring/alerts.yml`. All alerts assume the metrics
endpoints on the platform (`/actuator/prometheus`) and worker (`/metrics`)
are wired (see the integration note in `monitoring/prometheus.yml`).

### PlatformDown (critical)

Meaning: Prometheus has not been able to scrape the platform for 2 minutes.
The API, tool gateway, and dashboard data are affected; the worker cannot
reach the platform either.

First response:
1. `docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps platform` — is the container running?
2. Check platform logs: `docker compose ... logs --tail=200 platform`. Look for the error envelope `code` values (e.g. DB connection failures).
3. Verify postgres is reachable from the platform container: `docker compose ... exec platform pg_isready` is not installed — instead check `docker compose ... exec postgres pg_isready -U arl`.
4. If the container is crash-looping, roll back to the previous image tag (see `docs/PILOT.md#rollback`).

### WorkerDown (critical)

Meaning: the worker has not been scraped for 2 minutes. No evaluation jobs
are being processed; queued jobs wait in Redis.

First response:
1. Check worker container state and logs (`logs --tail=200 worker`).
2. Confirm Redis is up: `docker compose ... exec redis redis-cli ping` → `PONG`.
3. Confirm the worker can reach the platform: from the worker container, `python3 -c "import urllib.request; print(urllib.request.urlopen('http://platform:8080/api/health', timeout=5).status)"`.
4. Restart the worker: `docker compose ... up -d worker`. Abandoned jobs are reclaimed automatically (see "Job recovery" below).

### HighErrorRate (warning)

Meaning: more than 5% of platform requests returned 5xx over 5 minutes.

First response:
1. Platform logs — group by the error envelope `code` and `correlationId`.
2. Common causes: database connection exhaustion, a bad deployment, or a downstream provider outage (for live-mode calls).
3. If correlated with a deploy, roll back; otherwise open the platform logs around the first occurrence.

### HighLatency (warning)

Meaning: platform p95 latency above 2 seconds over 5 minutes.

First response:
1. Check DB latency (`pg_stat_statements` if enabled) and Redis latency (`redis-cli --latency`).
2. Check queue depth — a saturated worker does not raise platform latency directly, but eval submissions queue behind it.
3. If persistent, capture a load-test baseline (`load/load_test.py`) before and after the change.

### QueueDepthHigh (warning)

Meaning: more than 100 jobs waiting in Redis for 5 minutes — submissions
outpace processing.

First response:
1. Verify the worker is actually consuming (`WorkerDown` not firing; worker logs show completions).
2. Pause new evaluation submissions; let the queue drain.
3. Do not scale the worker horizontally without checking the platform's per-run action budgets and DB connection limits first.

### AbandonedJobs (warning)

Meaning: at least one job's processing lease expired and was reclaimed in
the last 15 minutes — the worker crashed or stalled mid-job.

First response:
1. Worker logs around the abandonment window for the job id.
2. The reclaimed job is re-queued; trial-completion records mean already-finished trials are skipped, so reclaimed jobs do not duplicate mutations (see "Job recovery").
3. Repeated abandonments → investigate worker memory/CPU or a poison job.

### HighRetryRate (warning)

Meaning: more than 20 job retries in 15 minutes — repeated transient
failures.

First response: identify the retry cause class in worker logs (provider
5xx/429, DB error, Redis error). Retries are bounded; if the budget is
exhausted the job fails loudly rather than looping forever.

### ProviderFailures (warning)

Meaning: more than 5 live-provider call failures in 15 minutes.

First response:
1. Check provider status and the `LLM_BASE_URL` reachability from the worker container.
2. Verify timeout and rate-limit handling engaged (see worker logs for retry/backoff entries).
3. Fixture-mode evaluations are unaffected; consider pausing live-mode submissions until the provider recovers.

### CostBudgetExceeded (warning)

Meaning: estimated evaluation spend above the configured hourly threshold
(default $10 — set to the agreed pilot budget in `monitoring/alerts.yml`).

First response:
1. Check `arl_llm_tokens_total` growth and which versions/scenarios consumed it.
2. Reduce live-mode concurrency or pause live evaluations. Estimates come from `eval/price-table.json` and are not provider billing.

## Redis durability and job recovery

- Redis runs with `--appendonly yes` (AOF): every write is logged, so queued
  jobs survive a Redis container restart with at most the last second of
  writes at risk (default `appendfsync everysec`).
- The worker uses processing leases: a job being executed holds a lease;
  if the worker dies, the lease expires and the job is reclaimed
  (abandoned-job recovery). Per-trial completion records mean a reclaimed
  job skips trials that already finished — retries do not duplicate
  mutations such as ticket updates or approval executions.
- Enqueue is deduplicated atomically (Lua script): submitting the same job
  twice does not create two queue entries.
- On worker restart, in-flight jobs (lease held by the dead worker) are
  reclaimed after lease expiry and re-run; jobs still waiting in the queue
  are untouched.

## Backup and restore

- `scripts/backup.sh` — dumps PostgreSQL (`pg_dump`, custom format) and
  copies evaluation evidence (`benchmarks/reports/`, `eval/recordings/` if
  present) into a timestamped directory with SHA-256 checksums.
- `scripts/restore.sh` — restores into a **named target database** (refuses
  to overwrite the source database without `--force`), then prints
  verification counts: tenants, agent versions, eval batches, trace events,
  approvals, release decisions — compare against the backup log.
- Full drill procedure (restore into a clean environment and verify):
  `docs/PILOT.md#backup-and-recovery-drill`.
- RTO/RPO are **proposed**, not measured: proposed RTO 1 hour, proposed RPO
  24 hours (daily backup). Measured recovery time and any data loss are
  recorded when the drill is actually run.

## Logs, traces, and redaction

- Secrets never appear in logs, traces, recordings, screenshots, or
  reports. API keys in recordings are redacted (`api_key=<redacted>`);
  trace-event payloads are sanitized at ingest.
- Do not log document contents at INFO or above; retrieval results are
  referenced by runbook slug/version, not quoted, in operational logs.
- Credential values must never be printed by scripts: `scripts/backup.sh`
  and `scripts/restore.sh` pass the database password via `PGPASSWORD`
  without echoing it.
