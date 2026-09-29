package com.agentreleaselab.api;

import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApprovalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Approval queue for human reviewers + execution endpoint. */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return approvals.list(TenantContext.get(), status).stream().map(this::view).toList();
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id) {
        return view(approvals.approve(TenantContext.get(), id));
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id) {
        return view(approvals.reject(TenantContext.get(), id));
    }

    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(@PathVariable UUID id) {
        var grant = approvals.execute(TenantContext.get(), id);
        return Map.of("executed", true, "targetUsername", grant.getTargetUsername(),
                "resource", grant.getResource(), "approvalId", id.toString());
    }

    private Map<String, Object> view(Approval a) {
        return Map.of(
                "id", a.getId().toString(),
                "tool", a.getToolName(),
                "args", a.getArgs(),
                "status", a.getStatus(),
                "requesterId", a.getRequesterId().toString(),
                "approverId", a.getApproverId() == null ? "" : a.getApproverId().toString(),
                "expiresAt", a.getExpiresAt().toString(),
                "createdAt", a.getCreatedAt().toString(),
                "fingerprint", a.getActionFingerprint().substring(0, 12) + "…");
    }
}
