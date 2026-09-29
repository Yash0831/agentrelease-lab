package com.agentreleaselab.service;

import com.agentreleaselab.domain.TraceEventRepository;
import com.agentreleaselab.domain.EvalRunRepository;
import com.agentreleaselab.domain.TraceEvent;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Auditable trace event store. Payloads are sanitized before persistence:
 *  API keys, tokens, and authorization headers are redacted (T10). */
@Service
public class TraceService {

    private static final Set<String> REDACT_KEYS = Set.of(
            "x-api-key", "api_key", "apikey", "authorization", "token",
            "llm_api_key", "secret", "password");

    private final TraceEventRepository events;
    private final EvalRunRepository evalRuns;

    public TraceService(TraceEventRepository events,
                        EvalRunRepository evalRuns) {
        this.events = events;
        this.evalRuns = evalRuns;
    }

    @Transactional
    public void record(UUID evalRunId, String traceId, String spanId, String parentSpanId,
                       String kind, String name, Map<String, Object> payload) {
        TenantContext ctx = TenantContext.get();
        UUID tenantId = ctx != null ? ctx.tenantId() : null;
        events.save(new TraceEvent(evalRunId, traceId, spanId, parentSpanId, kind, name,
                tenantId, sanitize(payload)));
    }

    @Transactional
    public void recordBatch(UUID evalRunId, List<Map<String, Object>> rawEvents) {
        for (Map<String, Object> e : rawEvents) {
            record(evalRunId,
                    (String) e.getOrDefault("trace_id", UUID.randomUUID().toString()),
                    (String) e.get("span_id"),
                    (String) e.get("parent_span_id"),
                    (String) e.getOrDefault("kind", "event"),
                    (String) e.getOrDefault("name", "event"),
                    (Map<String, Object>) e.getOrDefault("payload", Map.of()));
        }
    }

    /** Redact secret-looking keys recursively. Public for tests. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (REDACT_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                out.put(k, "***REDACTED***");
            } else if (v instanceof Map<?, ?> m) {
                out.put(k, sanitize((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                List<Object> nl = new ArrayList<>();
                for (Object i : l) nl.add(i instanceof Map ? sanitize((Map<String, Object>) i) : i);
                out.put(k, nl);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    public List<TraceEvent> timeline(UUID evalRunId) {
        // The eval run itself is tenant-scoped: a tenant can only read traces
        // for its own runs, even if it guesses another run's ID.
        evalRuns.findByIdAndTenantId(evalRunId, TenantContext.get().tenantId())
                .orElseThrow(() -> ApiException.notFound("EVAL_RUN_NOT_FOUND",
                        "No such eval run in your organization"));
        return events.findByEvalRunIdOrderByTsAscIdAsc(evalRunId);
    }
}
