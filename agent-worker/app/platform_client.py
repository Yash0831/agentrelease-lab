"""HTTP client for the platform API. The worker never touches business state
except through these endpoints (ADR-0001/0002)."""
from __future__ import annotations

import httpx

from .config import settings


class PlatformClient:
    def __init__(self, base_url: str | None = None, api_key: str | None = None,
                 timeout_s: float = 30.0):
        self.base_url = (base_url or settings.platform_base_url).rstrip("/")
        self.api_key = api_key or settings.platform_api_key
        self.timeout_s = timeout_s

    def _headers(self) -> dict:
        return {"X-API-Key": self.api_key, "Content-Type": "application/json"}

    def _req(self, method: str, path: str, **kwargs) -> httpx.Response:
        r = httpx.request(method, self.base_url + path, headers=self._headers(),
                          timeout=self.timeout_s, **kwargs)
        if r.status_code >= 400:
            # Surface the platform's machine-readable error envelope.
            try:
                body = r.json()
                code = body.get("code", f"HTTP_{r.status_code}")
                msg = body.get("error", r.text[:200])
            except Exception:
                code, msg = f"HTTP_{r.status_code}", r.text[:200]
            raise PlatformError(code, msg, r.status_code)
        return r

    # ---- tool gateway ----
    def tool_execute(self, *, tool: str, args: dict, idempotency_key: str,
                     eval_run_id: str | None = None, trace_id: str | None = None) -> dict:
        r = self._req("POST", "/api/tools/execute", json={
            "tool": tool, "args": args, "idempotencyKey": idempotency_key,
            "evalRunId": eval_run_id, "traceId": trace_id})
        return r.json()

    # ---- eval runs ----
    def eval_run_create(self, **kwargs) -> dict:
        return self._req("POST", "/api/eval-runs", json=kwargs).json()

    def eval_run_finish(self, run_id: str, status: str, metrics: dict,
                        error: str | None = None) -> dict:
        return self._req("PATCH", f"/api/eval-runs/{run_id}",
                         json={"status": status, "metrics": metrics,
                               "error": error}).json()

    # ---- traces ----
    def trace_events(self, eval_run_id: str, events: list[dict]) -> dict:
        return self._req("POST", "/api/traces/events",
                         json={"evalRunId": eval_run_id, "events": events}).json()

    def timeline(self, eval_run_id: str) -> list[dict]:
        return self._req("GET", "/api/traces/timeline",
                         params={"evalRunId": eval_run_id}).json()

    # ---- domain reads (for metric checkers) ----
    def get_ticket(self, key: str) -> dict:
        return self._req("GET", f"/api/tickets/{key}").json()

    def list_approvals(self, status: str | None = None) -> list[dict]:
        params = {"status": status} if status else {}
        return self._req("GET", "/api/approvals", params=params).json()

    def approve(self, approval_id: str) -> dict:
        return self._req("POST", f"/api/approvals/{approval_id}/approve").json()

    def execute_approval(self, approval_id: str) -> dict:
        return self._req("POST", f"/api/approvals/{approval_id}/execute").json()

    # ---- versions / release ----
    def register_version(self, **kwargs) -> dict:
        return self._req("POST", "/api/agent-versions", json=kwargs).json()

    def list_versions(self) -> list[dict]:
        return self._req("GET", "/api/agent-versions").json()

    def list_policies(self) -> list[dict]:
        return self._req("GET", "/api/release-policies").json()

    def evaluate_release(self, **kwargs) -> dict:
        return self._req("POST", "/api/release-decisions/evaluate", json=kwargs).json()


class PlatformError(Exception):
    def __init__(self, code: str, message: str, http_status: int):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.http_status = http_status
