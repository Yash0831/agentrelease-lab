# Platform security

## Authentication

Two paths, one `TenantContext` downstream:

- **API key** (`X-API-Key` header): local/dev and service identities. Keys are
  stored as SHA-256 hashes. The agent worker is a service identity: a dedicated
  api-key user with the `AGENT` role in its tenant, provisioned via
  `PLATFORM_API_KEY`.
- **OIDC** (`Authorization: Bearer <jwt>`): only when `ARL_OIDC_ENABLED=true`.
  The JWT is validated as RS256 against the provider JWKS (fetched and cached
  for 5 minutes): signature, exact `iss` match, `aud` containment, `exp` with
  60s clock skew. Required claims: `sub`, a tenant claim (configurable,
  default `tenant_slug`, falls back to `tenant_id`), a role claim (default
  `role`, must be one of `ADMIN/APPROVER/AGENT/REQUESTER/VIEWER`), and a scope
  claim (`scope` or `scp`, space-separated string or JSON array).

Unknown tenant or role → 401. Token material never appears in logs or errors.

## Scopes

`arl:evaluate` (submit eval runs), `arl:approve` (approve/reject/execute
approvals), `arl:admin` (release policy writes, fixture reset), `arl:read`
(read-only; currently informational).

Enforcement is scope-**or**-role: every gate also accepts the listed roles, so
existing API-key checks are unchanged. New gates:

| Operation | Gate |
|---|---|
| `POST /api/eval-runs` | `arl:evaluate` or `ADMIN`/`AGENT` role |
| `POST /api/eval-runs/fixtures/reset` | `arl:admin` or `ADMIN`/`AGENT` role; disabled in deployment mode (403 `FIXTURES_DISABLED`) |
| approve / reject approval | `arl:approve` or `APPROVER`/`ADMIN` |
| `POST /api/approvals/{id}/execute` | `arl:approve` or `APPROVER`/`ADMIN` |
| `POST /api/release-policies` | `arl:admin` or `ADMIN`/`AGENT` (the benchmark harness registers policies with the agent demo key) |

## Deployment mode (`ARL_MODE=deployment`)

Startup fails fast (`IllegalStateException`, no secret values in the message)
unless **all** hold: `SEED_ENABLED=false`, `SEED_ADMIN_API_KEY` set to a
non-default value, OIDC enabled with issuer/audience/JWKS URI configured, and
`ARL_CORS_ALLOWED_ORIGINS` not containing `*`. Local mode behavior is
unchanged.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `ARL_MODE` | `local` | `local` or `deployment` |
| `ARL_OIDC_ENABLED` | `false` | Enable OIDC bearer validation |
| `ARL_OIDC_ISSUER` | — | Expected `iss` (exact match) |
| `ARL_OIDC_AUDIENCE` | — | Expected `aud` (must be contained) |
| `ARL_OIDC_JWKS_URI` | — | Provider JWKS endpoint |
| `ARL_OIDC_TENANT_CLAIM` | `tenant_slug` | Claim carrying the tenant slug |
| `ARL_OIDC_ROLE_CLAIM` | `role` | Claim carrying the role |
| `ARL_OIDC_SCOPE_CLAIM` | `scope` | Claim carrying scopes |
| `ARL_CORS_ALLOWED_ORIGINS` | `*` | Comma-separated origins; `*` rejected in deployment mode |

## Local OIDC testing

The integration tests (`OidcAuthTest`) generate an RSA keypair in-test and
serve the JWKS from a stub HTTP server — no external IdP required. To test
against a real provider (e.g. Keycloak) locally:

```bash
export ARL_OIDC_ENABLED=true
export ARL_OIDC_ISSUER=http://localhost:8081/realms/arl
export ARL_OIDC_AUDIENCE=agentrelease-lab
export ARL_OIDC_JWKS_URI=http://localhost:8081/realms/arl/protocol/openid-connect/certs
```

then call the API with `Authorization: Bearer <token>`.
