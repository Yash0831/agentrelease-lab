package com.agentreleaselab.api;

import com.agentreleaselab.domain.UserRepository;
import com.agentreleaselab.domain.TicketRepository;
import com.agentreleaselab.domain.Ticket;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service desk tickets. All queries are tenant-scoped (ADR-0002). */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketRepository tickets;
    private final UserRepository users;

    public TicketController(TicketRepository tickets, UserRepository users) {
        this.tickets = tickets;
        this.users = users;
    }

    public record CreateTicket(@NotBlank String title, @NotBlank String description) {}
    public record TicketView(String ticketKey, String title, String description, String status,
                             String requester, String assignee, String createdAt, String updatedAt) {}

    @GetMapping
    public List<TicketView> list() {
        return tickets.findByTenantIdOrderByCreatedAtDesc(TenantContext.get().tenantId())
                .stream().map(this::view).toList();
    }

    @GetMapping("/{key}")
    public TicketView get(@PathVariable String key) {
        return view(require(key));
    }

    @PostMapping
    public TicketView create(@RequestBody CreateTicket body) {
        TenantContext ctx = TenantContext.get();
        String key = nextKey(ctx.tenantId());
        Ticket t = new Ticket(ctx.tenantId(), key, body.title(), body.description(), ctx.userId());
        return view(tickets.save(t));
    }

    public record UpdateStatus(@NotBlank String status) {}

    @PatchMapping("/{key}/status")
    public TicketView updateStatus(@PathVariable String key, @RequestBody UpdateStatus body) {
        if (!List.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED").contains(body.status())) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown ticket status");
        }
        Ticket t = require(key);
        t.setStatus(body.status());
        return view(tickets.save(t));
    }

    private Ticket require(String key) {
        return tickets.findByTenantIdAndTicketKey(TenantContext.get().tenantId(), key)
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "No such ticket in your organization"));
    }

    private String nextKey(UUID tenantId) {
        long n = tickets.countByTenantId(tenantId) + 101;
        String prefix = TenantContext.get().tenantSlug().equals("globex") ? "GLBX" : "ACME";
        return prefix + "-" + n;
    }

    private TicketView view(Ticket t) {
        String requester = t.getRequesterId() == null ? null :
                users.findById(t.getRequesterId()).map(u -> u.getDisplayName()).orElse(null);
        return new TicketView(t.getTicketKey(), t.getTitle(), t.getDescription(), t.getStatus(),
                requester, t.getAssignee(), t.getCreatedAt().toString(), t.getUpdatedAt().toString());
    }

    @GetMapping("/mine")
    public Map<String, String> mine() {
        TenantContext ctx = TenantContext.get();
        return Map.of("tenant", ctx.tenantSlug(), "username", ctx.username(), "role", ctx.role());
    }
}
