package com.agentreleaselab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Records an executed sensitive side effect. Written only by the approval
 *  execution path (ADR-0005). */
@Entity @Table(name = "access_grants")
public class AccessGrant {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "target_username", nullable = false) private String targetUsername;
    @Column(nullable = false) private String resource;
    @Column(name = "granted_by") private UUID grantedBy;
    @Column(name = "granted_at", nullable = false) private Instant grantedAt = Instant.now();

    protected AccessGrant() {}
    public AccessGrant(UUID tenantId, String targetUsername, String resource, UUID grantedBy) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.targetUsername = targetUsername;
        this.resource = resource; this.grantedBy = grantedBy;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getTargetUsername() { return targetUsername; }
    public String getResource() { return resource; }
}
