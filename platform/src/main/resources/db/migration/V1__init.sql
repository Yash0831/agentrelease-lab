-- AgentRelease Lab — initial schema (PostgreSQL 16 + pgvector)
-- All synthetic demo data is seeded by the application seeder and labeled as such.

CREATE EXTENSION IF NOT EXISTS vector;

-- ============ Tenants & users ============
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    username TEXT NOT NULL,
    display_name TEXT NOT NULL,
    api_key_hash TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK (role IN ('ADMIN','APPROVER','AGENT','REQUESTER')),
    phone TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, username)
);

-- ============ Service desk domain ============
CREATE TABLE runbooks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    slug TEXT NOT NULL,
    title TEXT NOT NULL,
    version INT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('CURRENT','STALE','ARCHIVED')),
    content TEXT NOT NULL,
    allowed_roles TEXT[] NOT NULL DEFAULT '{AGENT,ADMIN,APPROVER,REQUESTER}',
    embedding vector(384),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug, version)
);
CREATE INDEX runbooks_tenant_status_idx ON runbooks (tenant_id, status);

CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    ticket_key TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
    requester_id UUID REFERENCES app_users(id),
    assignee TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, ticket_key)
);

CREATE TABLE service_status (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    service_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPERATIONAL','DEGRADED','OUTAGE','MAINTENANCE')),
    message TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, service_name)
);

-- Sensitive-action side effects live here; only written via approved execution.
CREATE TABLE access_grants (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    target_username TEXT NOT NULL,
    resource TEXT NOT NULL,
    granted_by UUID REFERENCES app_users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, target_username, resource)
);

-- ============ Approval workflow ============
CREATE TABLE approvals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    tool_name TEXT NOT NULL,
    args_json JSONB NOT NULL,
    action_fingerprint TEXT NOT NULL,
    requester_id UUID NOT NULL REFERENCES app_users(id),
    approver_id UUID REFERENCES app_users(id),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','EXECUTED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX approvals_tenant_status_idx ON approvals (tenant_id, status);

-- ============ Agent versions & evaluation ============
CREATE TABLE agent_versions (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    prompt TEXT NOT NULL,
    model_id TEXT NOT NULL,
    retrieval_config JSONB NOT NULL,
    tool_schemas JSONB NOT NULL,
    doc_snapshot_id TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    fingerprint TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE eval_runs (
    id UUID PRIMARY KEY,
    agent_version_id UUID NOT NULL REFERENCES agent_versions(id),
    dataset_id TEXT NOT NULL,
    scenario_id TEXT NOT NULL,
    trial_index INT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('fixture','live','replay')),
    status TEXT NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','BLOCKED')),
    chaos_json JSONB,
    metrics_json JSONB,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error TEXT,
    UNIQUE (agent_version_id, dataset_id, scenario_id, trial_index, mode)
);

CREATE TABLE trace_events (
    id BIGSERIAL PRIMARY KEY,
    eval_run_id UUID REFERENCES eval_runs(id),
    trace_id TEXT NOT NULL,
    span_id TEXT,
    parent_span_id TEXT,
    ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    tenant_id UUID REFERENCES tenants(id),
    payload_json JSONB,
    sanitized BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX trace_events_run_idx ON trace_events (eval_run_id, ts);
CREATE INDEX trace_events_trace_idx ON trace_events (trace_id);

CREATE TABLE tool_calls (
    id UUID PRIMARY KEY,
    eval_run_id UUID REFERENCES eval_runs(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    tool_name TEXT NOT NULL,
    args_json JSONB NOT NULL,
    idempotency_key TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OK','ARG_INVALID','TOOL_UNKNOWN','DENIED','TIMEOUT','BUDGET_EXCEEDED','PENDING_APPROVAL','APPROVAL_DENIED','ERROR')),
    result_json JSONB,
    approval_id UUID REFERENCES approvals(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    duration_ms INT,
    UNIQUE (tenant_id, idempotency_key)
);

-- ============ Release gate ============
CREATE TABLE release_policies (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    thresholds_json JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE release_decisions (
    id UUID PRIMARY KEY,
    candidate_version_id UUID NOT NULL REFERENCES agent_versions(id),
    baseline_version_id UUID NOT NULL REFERENCES agent_versions(id),
    policy_id UUID NOT NULL REFERENCES release_policies(id),
    verdict TEXT NOT NULL CHECK (verdict IN ('PASS','FAIL','BLOCKED','INSUFFICIENT_EVIDENCE')),
    evidence_json JSONB NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
