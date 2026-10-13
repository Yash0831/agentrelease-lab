# Deployment — controlled pilot topology

This document describes how to run AgentRelease Lab as a controlled pilot:
who can reach what, where secrets live, and how identity is enforced.
`docker-compose.deploy.yml` is the Compose expression of this topology.
Factual scope: the overlay is config-reviewed; it has not been executed
here (no Docker daemon in this environment). Validate it in the pilot
environment before company use.

## Topology (text diagram)

```
                          ┌─────────────────────────────┐
                          │        reverse proxy         │
                          │  (TLS termination, WAF opt) │
                          │  e.g. Caddy / nginx / ALB   │
                          └──────────────┬──────────────┘
                                         │ 443 only, public
              ┌──────────────────────────┼──────────────────────────┐
              │  private compose network │                          │
              │                          │ 127.0.0.1-bound ports     │
     ┌────────┴────────┐        ┌────────┴────────┐        ┌─────────┴─────────┐
     │    platform     │        │     worker      │        │   prometheus      │
     │   :8080 (API)   │◄──────►│    :8001        │        │   :9090 (profile:  │
     │  read-only root │ Redis  │  read-only root │        │   monitoring)     │
     │  no-new-privs   │ queue  │  no-new-privs   │        └───────────────────┘
     └────────┬────────┘        └─────────────────┘
              │ JDBC                      ┌──────────────────┐
              ├──────────────────────────►│     postgres     │
              │                           │  NO host ports   │
              │  RESP                     │  volume: pgdata  │
              └──────────────────────────►│     redis        │
                                          │  NO host ports  │
                                          │  AOF enabled   │
                                          └──────────────────┘
```

- The reverse proxy is the only public surface and the only TLS endpoint.
  Application containers bind to `127.0.0.1` on the Docker host.
- `postgres` and `redis` publish **no** ports. They are reachable only over
  the private Compose network. Database administration uses
  `docker compose exec` from the host or the backup/restore scripts.
- `dashboard` is a static build; serve it behind the same reverse proxy
  (or the organization's static hosting) — it is not part of the deploy
  overlay because pilot access policy decides where it lives.
- For a managed database instead of the Compose postgres, remove the
  `postgres` service and point `DATABASE_URL` at the managed instance
  (private networking; TLS to the DB per the provider's docs).

## TLS termination

TLS terminates at the reverse proxy. The platform and worker speak plain
HTTP on the private network / loopback only. Example (Caddy):

```
arl-pilot.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Certificates come from the organization's ACME/internal CA via the proxy;
application containers never handle private keys.

## Identity: OIDC and service identities

Deployment mode (`ARL_MODE=deployment`) requires OIDC. The platform and
worker validate every request's bearer token:

- **issuer** (`ARL_OIDC_ISSUER`) — must match the token's `iss` exactly;
- **audience** (`ARL_OIDC_AUDIENCE`) — must match the token's `aud`;
- **signature** — verified against the JWKS at `ARL_OIDC_JWKS_URI`;
- **expiry** — `exp` must be in the future (clock-skew allowance is
  documented in the platform's OIDC configuration);
- **permissions** — scopes/roles in the token are mapped to the lab's
  roles (AGENT, APPROVER, ADMIN, REQUESTER); tenant comes from the token's
  tenant claim, never from request bodies.

Service identities (worker → platform) use scoped credentials issued by the
same identity provider (client-credentials flow) or a distinct
`WORKER_API_KEY` with the minimum platform role needed for job execution.
User authentication is OIDC; the demo API keys in the database are disabled
in deployment mode.

### Local identity provider for integration testing

For repeatable integration tests without a corporate IdP, run a local
OIDC provider (e.g. Dex, Keycloak, or `ory/hydra`) on the private network
and point the overlay at it:

```yaml
# add to a local-only override file (never commit real secrets)
services:
  platform:
    environment:
      ARL_MODE: deployment
      ARL_OIDC_ENABLED: "true"
      ARL_OIDC_ISSUER: http://dex:5556/dex
      ARL_OIDC_AUDIENCE: arl-pilot
      ARL_OIDC_JWKS_URI: http://dex:5556/dex/keys
```

Tests exercise: missing token → 401, expired token → 401, wrong audience →
401/403, cross-tenant token → 403 on another tenant's resources,
unauthorized role → 403, and approval execution requiring an APPROVER/ADMIN
identity (see the platform/worker test suites for the cases).

## Secrets

### What is secret

`POSTGRES_PASSWORD`, `SEED_ADMIN_API_KEY`, `WORKER_API_KEY`, `LLM_API_KEY`,
OIDC client secrets. The deploy overlay refuses to start when required
deployment secrets are missing or left at insecure defaults: required
variables use `${VAR:?message}` interpolation, so `docker compose config`
fails fast before any container starts.

### Secret-manager integration (cloud-agnostic pattern)

Do not commit `.env.deploy`. Generate it at deploy time from the secret
manager. Pattern (works with any manager that has a CLI):

```bash
# 1. Fetch secrets into a file only the deploy user can read.
umask 077
vault kv get -format=json secret/arl/pilot \
  | python3 -c "
import json,sys
s=json.load(sys.stdin)['data']['data']
for k in ('POSTGRES_PASSWORD','SEED_ADMIN_API_KEY','WORKER_API_KEY','LLM_API_KEY',
          'ARL_OIDC_ISSUER','ARL_OIDC_AUDIENCE','ARL_OIDC_JWKS_URI'):
    print(f'{k}={s[k]}')" > .env.deploy
chmod 600 .env.deploy

# 2. Compose reads it via --env-file (see docker-compose.deploy.yml header).
docker compose -f docker-compose.yml -f docker-compose.deploy.yml \
    --env-file .env.deploy up --build -d
```

Equivalents: `aws secretsmanager get-secret-value`, `gcloud secrets
versions access`, `az keyvault secret show`, 1Password `op read`, or SOPS /
age-encrypted files decrypted at deploy time. The contract is: secrets
enter the deployment as environment, never as committed files.

### File-based secrets (alternative)

Mount secret files at `/run/secrets/` with Compose `secrets:` and read
them at process start. The services currently read secrets from the
environment; file-based secrets require the entrypoints to export them
(e.g. `export POSTGRES_PASSWORD="$(cat /run/secrets/postgres_password)"`).
Documented here as the supported alternative; not yet wired.

### Credential rotation procedure

1. Generate the replacement secret in the secret manager (new version; keep
   the old version until cutover completes).
2. Regenerate `.env.deploy` and restart the affected service
   (`docker compose -f ... up -d platform` performs a rolling recreate).
3. Verify: service healthy, one authenticated request succeeds with the new
   credential, old credential rejected (401).
4. Disable/expire the old secret version in the manager.
5. Record the rotation (date, scope, operator) in the pilot log.

Rotate `LLM_API_KEY` and `WORKER_API_KEY` on the same schedule as the
organization's standard service-credential rotation. Database password
rotation requires a brief platform restart (no in-flight eval jobs during
the window, or drain the queue first).

## CORS

In deployment mode the platform only answers CORS preflights for origins
in `ARL_CORS_ALLOWED_ORIGINS` (comma-separated, exact match, no `*`).
The overlay requires it to be set. Example:

```
ARL_CORS_ALLOWED_ORIGINS=https://arl-pilot.example.com
```

## Deployment-mode environment set

| Variable | Meaning |
|---|---|
| `ARL_MODE=deployment` | Enables deployment posture: demo API keys and fixture-reset endpoints disabled; hardened checks on |
| `ARL_OIDC_ENABLED=true` | Require OIDC bearer-token auth |
| `ARL_OIDC_ISSUER` | Expected token issuer (exact match) |
| `ARL_OIDC_AUDIENCE` | Expected token audience |
| `ARL_OIDC_JWKS_URI` | JWKS endpoint for signature verification |
| `ARL_CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins (no wildcards) |
| `ARL_SECRETS_ENV_FILE` | Optional override for the secrets env file path (default `.env.deploy`) |

The exact OIDC variable names are a cross-service contract: the platform
and worker both read `ARL_MODE`, `ARL_OIDC_ENABLED`, `ARL_OIDC_ISSUER`,
`ARL_OIDC_AUDIENCE`, and `ARL_OIDC_JWKS_URI`.

## Monitoring wiring

Add `--profile monitoring` to start Prometheus with
`monitoring/prometheus.yml` and `monitoring/alerts.yml`. Prometheus binds
to `127.0.0.1:9090` on the host. Alert meanings and first responses are in
`docs/OPERATIONS.md`. The scrape jobs expect metrics endpoints on the
platform and worker (see the integration note in
`monitoring/prometheus.yml`); until those are wired, the up==0 alerts are
the honest "not wired" signal.

## Backup and restore

`scripts/backup.sh` dumps PostgreSQL plus evaluation evidence
(`benchmarks/reports`, recordings); `scripts/restore.sh` restores into a
named target database and prints verification counts. Redis uses AOF
(`--appendonly yes`); queued-job recovery behavior is in
`docs/OPERATIONS.md`. Full procedure: `docs/OPERATIONS.md#backup-and-restore`.
