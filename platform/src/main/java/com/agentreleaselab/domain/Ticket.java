package com.agentreleaselab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "tickets")
public class Ticket {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "ticket_key", nullable = false) private String ticketKey;
    @Column(nullable = false) private String title;
    @Column(nullable = false, columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private String status = "OPEN";
    @Column(name = "requester_id") private UUID requesterId;
    private String assignee;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected Ticket() {}
    public Ticket(UUID tenantId, String ticketKey, String title, String description, UUID requesterId) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.ticketKey = ticketKey;
        this.title = title; this.description = description; this.requesterId = requesterId;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getTicketKey() { return ticketKey; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; this.updatedAt = Instant.now(); }
    public UUID getRequesterId() { return requesterId; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String a) { this.assignee = a; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
