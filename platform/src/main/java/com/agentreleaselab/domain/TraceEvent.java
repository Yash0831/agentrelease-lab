package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "trace_events")
public class TraceEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "eval_run_id") private UUID evalRunId;
    @Column(name = "trace_id", nullable = false) private String traceId;
    @Column(name = "span_id") private String spanId;
    @Column(name = "parent_span_id") private String parentSpanId;
    @Column(nullable = false) private Instant ts = Instant.now();
    @Column(nullable = false) private String kind;
    @Column(nullable = false) private String name;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "payload_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;
    @Column(nullable = false) private boolean sanitized = true;

    protected TraceEvent() {}
    public TraceEvent(UUID evalRunId, String traceId, String spanId, String parentSpanId,
                      String kind, String name, UUID tenantId, Map<String, Object> payload) {
        this.evalRunId = evalRunId; this.traceId = traceId; this.spanId = spanId;
        this.parentSpanId = parentSpanId; this.kind = kind; this.name = name;
        this.tenantId = tenantId; this.payload = payload;
    }
    public Long getId() { return id; }
    public UUID getEvalRunId() { return evalRunId; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public Instant getTs() { return ts; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public UUID getTenantId() { return tenantId; }
    public Map<String, Object> getPayload() { return payload; }
    public boolean isSanitized() { return sanitized; }
}
