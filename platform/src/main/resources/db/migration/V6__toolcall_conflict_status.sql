-- V6: allow the CONFLICT status for idempotency-key conflicts (same key,
-- different tool or arguments). The gateway persists the conflict as a
-- terminal outcome so it is auditable like any other call.
ALTER TABLE tool_calls DROP CONSTRAINT tool_calls_status_check;
ALTER TABLE tool_calls ADD CONSTRAINT tool_calls_status_check CHECK (status IN (
    'OK','ARG_INVALID','TOOL_UNKNOWN','DENIED','TIMEOUT','BUDGET_EXCEEDED',
    'PENDING_APPROVAL','APPROVAL_DENIED','APPROVAL_NOT_APPROVED','ERROR',
    'INJECTION_BLOCKED','CONFLICT'));
