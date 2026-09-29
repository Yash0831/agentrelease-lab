package com.agentreleaselab.api;

import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApprovalService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Human/API-initiated access-request API. Creates a PENDING approval —
 *  the same object the agent's request_access tool creates. */
@RestController
@RequestMapping("/api/access-requests")
public class AccessRequestController {

    private final ApprovalService approvals;

    public AccessRequestController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    public record AccessRequest(@NotBlank String targetUsername, @NotBlank String resource,
                                String reason, String ticketKey) {}

    @PostMapping
    public Map<String, Object> request(@RequestBody AccessRequest body) {
        Approval a = approvals.propose(TenantContext.get(), body.targetUsername(),
                body.resource(), body.reason(), body.ticketKey());
        return Map.of("approvalId", a.getId().toString(), "status", a.getStatus(),
                "expiresAt", a.getExpiresAt().toString(),
                "message", "Request recorded. Awaiting human approval.");
    }
}
