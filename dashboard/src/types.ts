// Types mirror the platform API exactly. Loosely-typed JSON blobs (metrics,
// evidence, thresholds) are typed as unknown/Record and rendered defensively.

export interface AgentVersion {
  id: string;
  name: string;
  modelId: string;
  fingerprint: string;
  docSnapshotId: string;
  policyVersion: string;
}

export interface AgentVersionDetail extends AgentVersion {
  prompt: string;
  retrievalConfig: unknown;
  toolSchemas: unknown;
}

export interface Dataset {
  id: string;
  name: string;
  description: string;
  provenance: string;
  version: string;
  scenario_count: number;
  scenario_ids: string[];
}

export interface Scenario {
  id: string;
  description: string;
  task: string;
  expected_outcome: string;
  assertions: unknown;
  chaos: unknown;
}

export interface DatasetDetail {
  id: string;
  name: string;
  description: string;
  provenance: string;
  version: string;
  scenarios: Scenario[];
}

export interface EvalRun {
  id: string;
  scenarioId: string;
  trialIndex: number;
  mode: string;
  status: string;
  metrics: Record<string, unknown>;
  error: string | null;
}

export interface TraceEvent {
  id: string;
  traceId: string;
  spanId: string;
  ts: string;
  kind: string;
  name: string;
  payload: unknown;
  sanitized: boolean;
}

export interface Approval {
  id: string;
  tool: string;
  args: unknown;
  status: string;
  requesterId: string;
  approverId: string | null;
  expiresAt: string;
  createdAt: string;
  fingerprint: string;
}

export interface ReleasePolicy {
  id: string;
  name: string;
  thresholds: Record<string, unknown>;
}

export interface ReleaseDecisionSummary {
  id: string;
  verdict: string;
  batchId: string;
  candidateVersionId: string;
  baselineVersionId: string;
  decidedAt: string;
}

export interface ReleaseDecisionDetail {
  id: string;
  verdict: string;
  batchId: string;
  evidence: Record<string, unknown>;
  decidedAt: string;
}

export interface EvaluateRequest {
  candidateVersionId: string;
  baselineVersionId: string;
  policyId: string;
  datasetId: string;
  mode: string;
  batchId: string;
}
