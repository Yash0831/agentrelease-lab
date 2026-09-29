package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "tool_calls")
public class ToolCall {
    @Id private UUID id;
    @Column(name = "eval_run_id") private UUID evalRunId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tool_name", nullable = false) private String toolName;
    @Column(name = "args_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> args;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(nullable = false) private String status;
    @Column(name = "result_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;
    @Column(nullable = false) private boolean approved = false;
    @Column(name = "approval_id") private UUID approvalId;
    @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "duration_ms") private Integer durationMs;

    protected ToolCall() {}
    public ToolCall(UUID evalRunId, UUID tenantId, String toolName, Map<String, Object> args, String idempotencyKey) {
        this.id = UUID.randomUUID(); this.evalRunId = evalRunId; this.tenantId = tenantId;
        this.toolName = toolName; this.args = args; this.idempotencyKey = idempotencyKey;
    }
    public UUID getId() { return id; }
    public UUID getEvalRunId() { return evalRunId; }
    public UUID getTenantId() { return tenantId; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> r) { this.result = r; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean a) { this.approved = a; }
    public UUID getApprovalId() { return approvalId; }
    public void setApprovalId(UUID a) { this.approvalId = a; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer d) { this.durationMs = d; }
}
