import type {
  AgentVersion,
  AgentVersionDetail,
  Approval,
  Dataset,
  DatasetDetail,
  EvaluateRequest,
  EvalRun,
  ReleaseDecisionDetail,
  ReleaseDecisionSummary,
  ReleasePolicy,
  TraceEvent,
} from "./types";

const API_KEY_STORAGE = "arl-api-key";
export const DEFAULT_API_KEY = "arl-acme-admin-demo";

const BASE: string =
  (import.meta.env.VITE_PLATFORM_URL as string | undefined) ??
  "http://localhost:8080";

export function getApiKey(): string {
  try {
    return localStorage.getItem(API_KEY_STORAGE) ?? DEFAULT_API_KEY;
  } catch {
    return DEFAULT_API_KEY;
  }
}

export function setApiKey(key: string): void {
  try {
    localStorage.setItem(API_KEY_STORAGE, key);
  } catch {
    // storage unavailable; key stays in memory for this session
  }
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...init,
      headers: {
        "X-API-Key": getApiKey(),
        "Content-Type": "application/json",
        ...(init?.headers ?? {}),
      },
    });
  } catch (e) {
    throw new ApiError(
      0,
      `Network error reaching ${BASE}${path}: ${
        e instanceof Error ? e.message : String(e)
      }`
    );
  }
  if (!res.ok) {
    let body = "";
    try {
      body = (await res.text()).slice(0, 400);
    } catch {
      body = "";
    }
    const msg = body ? `${res.status}: ${body}` : `${res.status} ${res.statusText}`;
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  health: () => req<unknown>("/api/health"),
  versions: () => req<AgentVersion[]>("/api/agent-versions"),
  version: (id: string) => req<AgentVersionDetail>(`/api/agent-versions/${id}`),
  datasets: () => req<Dataset[]>("/api/datasets"),
  dataset: (id: string) => req<DatasetDetail>(`/api/datasets/${id}`),
  evalRuns: (agentVersionId: string) =>
    req<EvalRun[]>(`/api/eval-runs?agentVersionId=${encodeURIComponent(agentVersionId)}`),
  evalRun: (id: string) => req<EvalRun>(`/api/eval-runs/${id}`),
  traceTimeline: (evalRunId: string) =>
    req<TraceEvent[]>(`/api/traces/timeline?evalRunId=${encodeURIComponent(evalRunId)}`),
  approvals: (status: string) =>
    req<Approval[]>(`/api/approvals?status=${encodeURIComponent(status)}`),
  approve: (id: string) => req<Approval>(`/api/approvals/${id}/approve`, { method: "POST" }),
  reject: (id: string) => req<Approval>(`/api/approvals/${id}/reject`, { method: "POST" }),
  execute: (id: string) => req<Approval>(`/api/approvals/${id}/execute`, { method: "POST" }),
  policies: () => req<ReleasePolicy[]>("/api/release-policies"),
  evaluateDecision: (body: EvaluateRequest) =>
    req<ReleaseDecisionDetail>("/api/release-decisions/evaluate", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  decisions: () => req<ReleaseDecisionSummary[]>("/api/release-decisions"),
  decision: (id: string) => req<ReleaseDecisionDetail>(`/api/release-decisions/${id}`),
};
