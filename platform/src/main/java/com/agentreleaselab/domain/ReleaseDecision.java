package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "release_decisions")
public class ReleaseDecision {
    @Id private UUID id;
    @Column(name = "candidate_version_id", nullable = false) private UUID candidateVersionId;
    @Column(name = "baseline_version_id", nullable = false) private UUID baselineVersionId;
    @Column(name = "policy_id", nullable = false) private UUID policyId;
    @Column(nullable = false) private String verdict;
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> evidence;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt = Instant.now();

    protected ReleaseDecision() {}
    public ReleaseDecision(UUID candidateVersionId, UUID baselineVersionId, UUID policyId,
                           String verdict, Map<String, Object> evidence) {
        this.id = UUID.randomUUID(); this.candidateVersionId = candidateVersionId;
        this.baselineVersionId = baselineVersionId; this.policyId = policyId;
        this.verdict = verdict; this.evidence = evidence;
    }
    public UUID getId() { return id; }
    public UUID getCandidateVersionId() { return candidateVersionId; }
    public UUID getBaselineVersionId() { return baselineVersionId; }
    public UUID getPolicyId() { return policyId; }
    public String getVerdict() { return verdict; }
    public Map<String, Object> getEvidence() { return evidence; }
    public Instant getDecidedAt() { return decidedAt; }
}
