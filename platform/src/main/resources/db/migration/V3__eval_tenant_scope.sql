-- V3: tenant ownership for evaluation records. Execution records, release
-- verdicts, policies, and agent versions belong to exactly one tenant, so one
-- organization can never read another's evaluation data by ID (defense in
-- depth alongside the service-desk tenant isolation).
ALTER TABLE eval_runs ADD COLUMN tenant_id UUID REFERENCES tenants(id);
ALTER TABLE release_decisions ADD COLUMN tenant_id UUID REFERENCES tenants(id);
ALTER TABLE release_policies ADD COLUMN tenant_id UUID REFERENCES tenants(id);
ALTER TABLE agent_versions ADD COLUMN tenant_id UUID REFERENCES tenants(id);

-- Version/policy names and fingerprints are unique per tenant, not globally.
ALTER TABLE agent_versions DROP CONSTRAINT agent_versions_name_key;
ALTER TABLE agent_versions ADD CONSTRAINT agent_versions_tenant_name_key UNIQUE (tenant_id, name);
ALTER TABLE agent_versions DROP CONSTRAINT agent_versions_fingerprint_key;
ALTER TABLE agent_versions ADD CONSTRAINT agent_versions_tenant_fingerprint_key UNIQUE (tenant_id, fingerprint);
ALTER TABLE release_policies DROP CONSTRAINT release_policies_name_key;
ALTER TABLE release_policies ADD CONSTRAINT release_policies_tenant_name_key UNIQUE (tenant_id, name);
