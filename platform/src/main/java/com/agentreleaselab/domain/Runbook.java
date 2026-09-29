package com.agentreleaselab.domain;

import com.pgvector.PGvector;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "runbooks")
public class Runbook {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String slug;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private int version;
    @Column(nullable = false) private String status;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "allowed_roles", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] allowedRoles = {"AGENT","ADMIN","APPROVER","REQUESTER"};
    @Column(columnDefinition = "vector(384)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    private PGvector embedding;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected Runbook() {}
    public Runbook(UUID tenantId, String slug, String title, int version, String status, String content, String[] allowedRoles) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.slug = slug; this.title = title;
        this.version = version; this.status = status; this.content = content; this.allowedRoles = allowedRoles;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public int getVersion() { return version; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getContent() { return content; }
    public String[] getAllowedRoles() { return allowedRoles; }
    public PGvector getEmbedding() { return embedding; }
    public void setEmbedding(PGvector e) { this.embedding = e; }
}
