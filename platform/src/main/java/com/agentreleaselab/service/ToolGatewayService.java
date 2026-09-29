package com.agentreleaselab.service;

import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.domain.Ticket;
import com.agentreleaselab.domain.ToolCall;
import com.agentreleaselab.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Tool execution gateway (ADR-0002). The model is untrusted; every call is
 *  validated here: allowlist, JSON-schema argument checks, per-run action
 *  budgets, timeouts, idempotency keys. Sensitive actions never execute
 *  directly — they produce approvals (ADR-0005). */
@Service
public class ToolGatewayService {

    public static final Set<String> AGENT_TOOLS = Set.of(
            "search_runbooks", "get_ticket", "update_ticket_status",
            "get_service_status", "request_access", "execute_approval");

    private static final Set<String> TICKET_STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private final Repositories.ToolCallRepository toolCalls;
    private final Repositories.TicketRepository tickets;
    private final Repositories.ServiceStatusRepository serviceStatus;
    private final Repositories.EvalRunRepository evalRunRepo;
    private final RetrievalService retrieval;
    private final ApprovalService approvals;
    private final TraceService traces;

    private final int defaultTimeoutMs;
    private final int maxActionBudget;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ToolGatewayService(Repositories.ToolCallRepository toolCalls,
                              Repositories.TicketRepository tickets,
                              Repositories.ServiceStatusRepository serviceStatus,
                              Repositories.EvalRunRepository evalRunRepo,
                              RetrievalService retrieval,
                              ApprovalService approvals,
                              TraceService traces,
                              @Value("${arl.gateway.default-timeout-ms:5000}") int defaultTimeoutMs,
                              @Value("${arl.gateway.max-action-budget:25}") int maxActionBudget) {
        this.toolCalls = toolCalls;
        this.tickets = tickets;
        this.serviceStatus = serviceStatus;
        this.evalRunRepo = evalRunRepo;
        this.retrieval = retrieval;
        this.approvals = approvals;
        this.traces = traces;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxActionBudget = maxActionBudget;
    }

    public record ToolOutcome(String status, Map<String, Object> result, String errorCode,
                              String errorMessage, UUID approvalId, boolean idempotentReplay) {}

    /** Chaos knobs a failure-injection scenario may set for this eval run. */
    public record ChaosConfig(Map<String, Object> raw) {
        public long toolDelayMs(String tool) {
            Object delays = raw.get("tool_delays_ms");
            if (delays instanceof Map<?, ?> m) {
                Object v = m.get(tool);
                if (v instanceof Number n) return n.longValue();
            }
            return 0;
        }
        public int timeoutMsOverride() {
            Object v = raw.get("gateway_timeout_ms");
            return v instanceof Number n ? n.intValue() : -1;
        }
        public int budgetOverride() {
            Object v = raw.get("action_budget");
            return v instanceof Number n ? n.intValue() : -1;
        }
        @SuppressWarnings("unchecked")
        public Map<String, Object> forcedError(String tool) {
            Object e = raw.get("forced_tool_errors");
            if (e instanceof Map<?, ?> m) {
                Object v = m.get(tool);
                if (v instanceof Map<?, ?> vm) return (Map<String, Object>) vm;
            }
            return null;
        }
    }

    @Transactional
    public ToolOutcome execute(String toolName, Map<String, Object> args, String idempotencyKey,
                               UUID evalRunId, String traceId) {
        return execute(toolName, args, idempotencyKey, evalRunId, traceId, null);
    }

    /** Explicit chaos overrides the eval run's chaos when non-empty (tests, drills). */
    @Transactional
    public ToolOutcome execute(String toolName, Map<String, Object> args, String idempotencyKey,
                               UUID evalRunId, String traceId, ChaosConfig explicitChaos) {
        TenantContext ctx = TenantContext.get();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Mutating-safe gateway requires an Idempotency-Key for every call");
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : new LinkedHashMap<>(args);
        // Chaos knobs come from the eval run record (failure-injection lab),
        // unless an explicit non-empty chaos config is provided.
        ChaosConfig chaos = (explicitChaos != null && !explicitChaos.raw().isEmpty())
                ? explicitChaos : loadChaos(evalRunId);

        // 1. Idempotency: a repeated key returns the ORIGINAL result without re-executing.
        Optional<ToolCall> existing = toolCalls.findByTenantIdAndIdempotencyKey(ctx.tenantId(), idempotencyKey);
        if (existing.isPresent()) {
            ToolCall prev = existing.get();
            Map<String, Object> r = new LinkedHashMap<>(prev.getResult() == null ? Map.of() : prev.getResult());
            r.put("idempotent_replay", true);
            return new ToolOutcome(prev.getStatus(), r, null, null, prev.getApprovalId(), true);
        }

        // 2. Allowlist (unknown tools can never reach business logic).
        if (!AGENT_TOOLS.contains(toolName)) {
            return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    "TOOL_UNKNOWN", null, "TOOL_UNKNOWN",
                    "Tool '" + toolName + "' is not in the agent allowlist", null, traceId);
        }

        // 3. Schema validation.
        List<String> violations = validateArgs(toolName, safeArgs);
        if (!violations.isEmpty()) {
            return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    "ARG_INVALID", Map.of("violations", violations), "ARG_INVALID",
                    "Argument validation failed: " + String.join("; ", violations), null, traceId);
        }

        // 4. Action budget (per eval run).
        int budget = chaos.budgetOverride() > 0 ? chaos.budgetOverride() : maxActionBudget;
        if (evalRunId != null) {
            long used = toolCalls.findByEvalRunIdOrderByStartedAtAsc(evalRunId).size();
            if (used >= budget) {
                return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                        "BUDGET_EXCEEDED", null, "BUDGET_EXCEEDED",
                        "Action budget exhausted (" + used + "/" + budget + " tool calls)", null, traceId);
            }
        }

        // 5. Forced chaos error (e.g. provider outage simulation at the tool layer).
        Map<String, Object> forced = chaos.forcedError(toolName);
        if (forced != null) {
            String code = String.valueOf(forced.getOrDefault("code", "ERROR"));
            String msg = String.valueOf(forced.getOrDefault("message", "Forced chaos error"));
            return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    "ERROR", null, code, msg, null, traceId);
        }

        // 6. Execute with timeout. Sensitive tools route to the approval workflow.
        int timeoutMs = chaos.timeoutMsOverride() > 0 ? chaos.timeoutMsOverride() : defaultTimeoutMs;
        long delayMs = chaos.toolDelayMs(toolName);
        Instant start = Instant.now();
        try {
            Map<String, Object> result;
            String status;
            UUID approvalId = null;
            // Propagate the request's TenantContext onto the worker thread (ADR-0002:
            // tenant identity must survive the timeout boundary).
            Future<ToolOutcome> future = executor.submit(() -> {
                TenantContext.set(ctx);
                try {
                    if (delayMs > 0) Thread.sleep(delayMs);
                    return dispatch(toolName, safeArgs, evalRunId, traceId, chaos);
                } finally {
                    TenantContext.clear();
                }
            });
            ToolOutcome inner;
            try {
                inner = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                throw te;
            }
            status = inner.status();
            result = inner.result();
            approvalId = inner.approvalId();
            long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
            return persistWithDuration(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    status, result, inner.errorCode(), inner.errorMessage(), approvalId, traceId, (int) durationMs);
        } catch (TimeoutException te) {
            return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    "TIMEOUT", null, "TOOL_TIMEOUT",
                    "Tool '" + toolName + "' exceeded timeout of " + timeoutMs + "ms", null, traceId);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            if (cause instanceof ApiException ae) {
                return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                        ae.getCode().equals("APPROVAL_DENIED") ? "APPROVAL_DENIED" : "DENIED",
                        null, ae.getCode(), ae.getMessage(), null, traceId);
            }
            return persist(ctx, evalRunId, toolName, safeArgs, idempotencyKey,
                    "ERROR", null, "TOOL_ERROR", "Tool execution failed: " + cause.getMessage(), null, traceId);
        }
    }

    private ChaosConfig loadChaos(UUID evalRunId) {
        if (evalRunId == null) return new ChaosConfig(Map.of());
        return evalRunRepo.findById(evalRunId)
                .map(r -> new ChaosConfig(r.getChaos() == null ? Map.of() : r.getChaos()))
                .orElse(new ChaosConfig(Map.of()));
    }

    /** Runs inside the timeout future. Sensitive actions create approvals; nothing else mutates. */
    private ToolOutcome dispatch(String toolName, Map<String, Object> args, UUID evalRunId,
                                 String traceId, ChaosConfig chaos) {
        TenantContext ctx = TenantContext.get();
        return switch (toolName) {
            case "search_runbooks" -> {
                // Chaos: force empty retrieval (failure-injection lab).
                if (Boolean.TRUE.equals(chaos.raw().get("force_empty_retrieval"))) {
                    yield new ToolOutcome("OK", Map.of("results", List.of()),
                            null, null, null, false);
                }
                int topK = args.get("topK") instanceof Number n ? n.intValue() : 5;
                var hits = retrieval.search(String.valueOf(args.get("query")), topK);
                List<Map<String, Object>> out = new ArrayList<>();
                hits.forEach(h -> out.add(Map.of(
                        "slug", h.slug(), "title", h.title(), "version", h.version(),
                        "status", h.status(), "snippet", h.snippet(), "score", h.score())));
                yield new ToolOutcome("OK", Map.of("results", out), null, null, null, false);
            }
            case "get_ticket" -> {
                Ticket t = tickets.findByTenantIdAndTicketKey(ctx.tenantId(), String.valueOf(args.get("ticketKey")))
                        .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "No such ticket"));
                yield new ToolOutcome("OK", Map.of(
                        "ticketKey", t.getTicketKey(), "title", t.getTitle(),
                        "description", t.getDescription(), "status", t.getStatus(),
                        "assignee", t.getAssignee() == null ? "" : t.getAssignee()), null, null, null, false);
            }
            case "update_ticket_status" -> {
                Ticket t = tickets.findByTenantIdAndTicketKey(ctx.tenantId(), String.valueOf(args.get("ticketKey")))
                        .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "No such ticket"));
                String before = t.getStatus();
                t.setStatus(String.valueOf(args.get("status")));
                tickets.save(t);
                yield new ToolOutcome("OK", Map.of("ticketKey", t.getTicketKey(),
                        "previousStatus", before, "status", t.getStatus()), null, null, null, false);
            }
            case "get_service_status" -> {
                Object name = args.get("serviceName");
                List<Map<String, Object>> out = new ArrayList<>();
                if (name != null && !String.valueOf(name).isBlank()) {
                    serviceStatus.findByTenantIdAndServiceName(ctx.tenantId(), String.valueOf(name))
                            .ifPresent(s -> out.add(Map.of("serviceName", s.getServiceName(),
                                    "status", s.getStatus(), "message", s.getMessage() == null ? "" : s.getMessage())));
                } else {
                    serviceStatus.findByTenantId(ctx.tenantId())
                            .forEach(s -> out.add(Map.of("serviceName", s.getServiceName(),
                                    "status", s.getStatus(), "message", s.getMessage() == null ? "" : s.getMessage())));
                }
                yield new ToolOutcome("OK", Map.of("services", out), null, null, null, false);
            }
            case "request_access" -> {
                // Sensitive: creates a PENDING approval, performs NO side effect (ADR-0005).
                var approval = approvals.propose(ctx, String.valueOf(args.get("targetUsername")),
                        String.valueOf(args.get("resource")),
                        args.get("reason") == null ? "" : String.valueOf(args.get("reason")),
                        args.get("ticketKey") == null ? null : String.valueOf(args.get("ticketKey")));
                yield new ToolOutcome("PENDING_APPROVAL",
                        Map.of("approvalId", approval.getId().toString(), "status", "PENDING",
                                "message", "Access request recorded. A human reviewer must approve before it can be executed."),
                        null, null, approval.getId(), false);
            }
            case "execute_approval" -> {
                UUID approvalId = UUID.fromString(String.valueOf(args.get("approvalId")));
                var grant = approvals.execute(ctx, approvalId);
                yield new ToolOutcome("OK", Map.of("executed", true,
                        "targetUsername", grant.getTargetUsername(), "resource", grant.getResource()),
                        null, null, approvalId, false);
            }
            default -> throw new IllegalStateException("Unreachable: allowlist checked earlier");
        };
    }

    /** Minimal JSON-schema-style validation per tool. Returns violation messages. */
    private List<String> validateArgs(String tool, Map<String, Object> args) {
        List<String> v = new ArrayList<>();
        switch (tool) {
            case "search_runbooks" -> {
                checkString(args, "query", 1, 500, true, v);
                if (args.get("topK") instanceof Number n && (n.intValue() < 1 || n.intValue() > 10))
                    v.add("topK must be between 1 and 10");
            }
            case "get_ticket" -> checkString(args, "ticketKey", 1, 64, true, v);
            case "update_ticket_status" -> {
                checkString(args, "ticketKey", 1, 64, true, v);
                Object s = args.get("status");
                if (!(s instanceof String) || !TICKET_STATUSES.contains(s))
                    v.add("status must be one of " + TICKET_STATUSES);
            }
            case "get_service_status" -> checkString(args, "serviceName", 1, 128, false, v);
            case "request_access" -> {
                checkString(args, "targetUsername", 1, 128, true, v);
                checkString(args, "resource", 1, 128, true, v);
                checkString(args, "reason", 1, 500, false, v);
                checkString(args, "ticketKey", 1, 64, false, v);
            }
            case "execute_approval" -> {
                try { UUID.fromString(String.valueOf(args.get("approvalId"))); }
                catch (Exception e) { v.add("approvalId must be a UUID"); }
            }
            default -> v.add("unknown tool");
        }
        return v;
    }

    private void checkString(Map<String, Object> args, String field, int min, int max,
                             boolean required, List<String> violations) {
        Object val = args.get(field);
        if (val == null) {
            if (required) violations.add(field + " is required");
            return;
        }
        if (!(val instanceof String s) || s.length() < min || s.length() > max) {
            violations.add(field + " must be a string of length " + min + ".." + max);
        }
    }

    private ToolOutcome persist(TenantContext ctx, UUID evalRunId, String toolName, Map<String, Object> args,
                                String idempotencyKey, String status, Map<String, Object> result,
                                String errorCode, String errorMessage, UUID approvalId, String traceId) {
        return persistWithDuration(ctx, evalRunId, toolName, args, idempotencyKey, status, result,
                errorCode, errorMessage, approvalId, traceId, 0);
    }

    private ToolOutcome persistWithDuration(TenantContext ctx, UUID evalRunId, String toolName,
                                            Map<String, Object> args, String idempotencyKey, String status,
                                            Map<String, Object> result, String errorCode, String errorMessage,
                                            UUID approvalId, String traceId, int durationMs) {
        ToolCall call = new ToolCall(evalRunId, ctx.tenantId(), toolName, args, idempotencyKey);
        call.setStatus(status);
        Map<String, Object> stored = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
        if (errorCode != null) { stored.put("error_code", errorCode); stored.put("error_message", errorMessage); }
        call.setResult(stored);
        call.setApprovalId(approvalId);
        call.setFinishedAt(Instant.now());
        call.setDurationMs(durationMs);
        try {
            toolCalls.save(call);
        } catch (DataIntegrityViolationException race) {
            // Lost a race on the idempotency key: return the winner's result.
            return toolCalls.findByTenantIdAndIdempotencyKey(ctx.tenantId(), idempotencyKey)
                    .map(prev -> {
                        Map<String, Object> r = new LinkedHashMap<>(prev.getResult() == null ? Map.of() : prev.getResult());
                        r.put("idempotent_replay", true);
                        return new ToolOutcome(prev.getStatus(), r, null, null, prev.getApprovalId(), true);
                    })
                    .orElseThrow(() -> race);
        }
        if (traceId != null) {
            traces.record(evalRunId, traceId, UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    null, "tool_call", toolName,
                    Map.of("status", status, "duration_ms", durationMs,
                            "error_code", errorCode == null ? "" : errorCode,
                            "idempotent_replay", false));
        }
        Map<String, Object> out = new LinkedHashMap<>(stored);
        return new ToolOutcome(status, out, errorCode, errorMessage, approvalId, false);
    }
}
