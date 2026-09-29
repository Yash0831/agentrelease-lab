package com.agentreleaselab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "service_status")
public class ServiceStatus {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "service_name", nullable = false) private String serviceName;
    @Column(nullable = false) private String status;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected ServiceStatus() {}
    public ServiceStatus(UUID tenantId, String serviceName, String status, String message) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.serviceName = serviceName;
        this.status = status; this.message = message;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getServiceName() { return serviceName; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; this.updatedAt = Instant.now(); }
    public String getMessage() { return message; }
    public void setMessage(String m) { this.message = m; this.updatedAt = Instant.now(); }
    public Instant getUpdatedAt() { return updatedAt; }
}
