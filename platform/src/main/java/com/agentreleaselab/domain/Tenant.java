package com.agentreleaselab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "tenants")
public class Tenant {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String slug;
    @Column(nullable = false) private String name;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected Tenant() {}
    public Tenant(String slug, String name) {
        this.id = UUID.randomUUID(); this.slug = slug; this.name = name;
    }
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
}
