package com.agentreleaselab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "release_policies")
public class ReleasePolicy {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String name;
    @Column(name = "thresholds_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> thresholds;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected ReleasePolicy() {}
    public ReleasePolicy(UUID tenantId, String name, Map<String, Object> thresholds) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.name = name; this.thresholds = thresholds;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public Map<String, Object> getThresholds() { return thresholds; }
    public boolean isActive() { return active; }
}
