package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "approvals")
public class Approval {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tool_name", nullable = false) private String toolName;
    @Column(name = "args_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> args;
    @Column(name = "action_fingerprint", nullable = false) private String actionFingerprint;
    @Column(name = "requester_id", nullable = false) private UUID requesterId;
    @Column(name = "approver_id") private UUID approverId;
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "executed_at") private Instant executedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected Approval() {}
    public Approval(UUID tenantId, String toolName, Map<String, Object> args, String fingerprint,
                    UUID requesterId, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.toolName = toolName;
        this.args = args; this.actionFingerprint = fingerprint; this.requesterId = requesterId;
        this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
    /** Test/maintenance hook — normal code never mutates args after creation. */
    public void setArgs(Map<String, Object> args) { this.args = args; }
    public String getActionFingerprint() { return actionFingerprint; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getApproverId() { return approverId; }
    public void setApproverId(UUID a) { this.approverId = a; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant t) { this.executedAt = t; }
    public Instant getCreatedAt() { return createdAt; }
}
