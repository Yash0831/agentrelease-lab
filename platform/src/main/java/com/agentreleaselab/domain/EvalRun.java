package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "eval_runs")
public class EvalRun {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "agent_version_id", nullable = false) private UUID agentVersionId;
    @Column(name = "dataset_id", nullable = false) private String datasetId;
    @Column(name = "scenario_id", nullable = false) private String scenarioId;
    @Column(name = "trial_index", nullable = false) private int trialIndex;
    @Column(nullable = false) private String mode;
    @Column(nullable = false) private String status = "QUEUED";
    @Column(name = "chaos_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> chaos;
    @Column(name = "metrics_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metrics;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(columnDefinition = "TEXT") private String error;

    protected EvalRun() {}
    public EvalRun(UUID tenantId, UUID agentVersionId, String datasetId, String scenarioId, int trialIndex, String mode, Map<String, Object> chaos) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.agentVersionId = agentVersionId;
        this.datasetId = datasetId; this.scenarioId = scenarioId;
        this.trialIndex = trialIndex; this.mode = mode; this.chaos = chaos;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgentVersionId() { return agentVersionId; }
    public String getDatasetId() { return datasetId; }
    public String getScenarioId() { return scenarioId; }
    public int getTrialIndex() { return trialIndex; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Map<String, Object> getChaos() { return chaos; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> m) { this.metrics = m; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
    public String getError() { return error; }
    public void setError(String e) { this.error = e; }
}
