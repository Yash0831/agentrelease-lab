-- V4: allow the INJECTION_BLOCKED tool-call status (prompt-injection
-- tripwire, ADR-0002). The tripwire persists a blocked record so the attempt
-- is auditable in the trace timeline.
ALTER TABLE tool_calls DROP CONSTRAINT tool_calls_status_check;
ALTER TABLE tool_calls ADD CONSTRAINT tool_calls_status_check CHECK (
    status IN ('OK','ARG_INVALID','TOOL_UNKNOWN','DENIED','TIMEOUT',
               'BUDGET_EXCEEDED','PENDING_APPROVAL','APPROVAL_DENIED',
               'INJECTION_BLOCKED','ERROR'));
