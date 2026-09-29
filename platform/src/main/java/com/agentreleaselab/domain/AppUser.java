package com.agentreleaselab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "app_users")
public class AppUser {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String username;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "api_key_hash", nullable = false, unique = true) private String apiKeyHash;
    @Column(nullable = false) private String role;
    private String phone;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AppUser() {}
    public AppUser(UUID tenantId, String username, String displayName, String apiKeyHash, String role, String phone) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.username = username;
        this.displayName = displayName; this.apiKeyHash = apiKeyHash; this.role = role; this.phone = phone;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getApiKeyHash() { return apiKeyHash; }
    public String getRole() { return role; }
    public String getPhone() { return phone; }
}
