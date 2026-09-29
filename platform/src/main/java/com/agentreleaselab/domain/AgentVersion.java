package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "agent_versions")
public class AgentVersion {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String name;
    @Column(nullable = false, columnDefinition = "TEXT") private String prompt;
    @Column(name = "model_id", nullable = false) private String modelId;
    @Column(name = "retrieval_config", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> retrievalConfig;
    @Column(name = "tool_schemas", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> toolSchemas;
    @Column(name = "doc_snapshot_id", nullable = false) private String docSnapshotId;
    @Column(name = "policy_version", nullable = false) private String policyVersion;
    @Column(nullable = false) private String fingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AgentVersion() {}
    public AgentVersion(UUID tenantId, String name, String prompt, String modelId, Map<String, Object> retrievalConfig,
                        Map<String, Object> toolSchemas, String docSnapshotId, String policyVersion, String fingerprint) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.name = name; this.prompt = prompt; this.modelId = modelId;
        this.retrievalConfig = retrievalConfig; this.toolSchemas = toolSchemas;
        this.docSnapshotId = docSnapshotId; this.policyVersion = policyVersion; this.fingerprint = fingerprint;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getPrompt() { return prompt; }
    public String getModelId() { return modelId; }
    public Map<String, Object> getRetrievalConfig() { return retrievalConfig; }
    public Map<String, Object> getToolSchemas() { return toolSchemas; }
    public String getDocSnapshotId() { return docSnapshotId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getFingerprint() { return fingerprint; }
}
