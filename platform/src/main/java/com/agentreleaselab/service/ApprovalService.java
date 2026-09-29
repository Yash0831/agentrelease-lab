package com.agentreleaselab.service;

import com.agentreleaselab.domain.UserRepository;
import com.agentreleaselab.domain.ApprovalRepository;
import com.agentreleaselab.domain.AccessGrantRepository;
import com.agentreleaselab.domain.AccessGrant;
import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.security.TenantContext;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Approval workflow (ADR-0005). The agent proposes via request_access; a human
 *  approves; execution revalidates everything (fingerprint, expiry, tenant,
 *  separation of duties) inside one transaction with a row lock. */
@Service
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final AccessGrantRepository grants;
    private final UserRepository users;

    public ApprovalService(ApprovalRepository approvals,
                           AccessGrantRepository grants,
                           UserRepository users) {
        this.approvals = approvals;
        this.grants = grants;
        this.users = users;
    }

    /** Agent-facing proposal. No side effect — returns a PENDING approval. */
    @Transactional
    public Approval propose(TenantContext ctx, String targetUsername, String resource,
                            String reason, String ticketKey) {
        if (!ctx.hasAnyRole("AGENT", "ADMIN", "REQUESTER")) {
            throw ApiException.forbidden("APPROVAL_PROPOSE_DENIED", "Role may not propose access changes");
        }
        // Target user must exist in the same tenant.
        users.findByTenantIdAndUsername(ctx.tenantId(), targetUsername)
                .orElseThrow(() -> ApiException.notFound("TARGET_USER_NOT_FOUND",
                        "No such user in your organization: " + targetUsername));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("targetUsername", targetUsername);
        args.put("resource", resource);
        if (reason != null && !reason.isBlank()) args.put("reason", reason);
        if (ticketKey != null && !ticketKey.isBlank()) args.put("ticketKey", ticketKey);
        // Truncate to micros: timestamptz cannot store nanos, so the fingerprint
        // must be computed on the value that survives the DB round-trip (ADR-0005).
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        String fingerprint = FingerprintService.actionFingerprint(
                "grant_access", args, ctx.userId(), ctx.tenantId(), expiresAt.toString());
        Approval approval = new Approval(ctx.tenantId(), "grant_access", args, fingerprint,
                ctx.userId(), expiresAt);
        return approvals.save(approval);
    }

    @Transactional
    public Approval approve(TenantContext ctx, UUID approvalId) {
        Approval a = loadForUpdateForTenant(ctx, approvalId);
        if (!ctx.hasAnyRole("APPROVER", "ADMIN")) {
            throw ApiException.forbidden("APPROVAL_APPROVE_DENIED", "Role may not approve access changes");
        }
        if (!a.getStatus().equals("PENDING")) {
            throw ApiException.conflict("APPROVAL_NOT_PENDING", "Approval is " + a.getStatus());
        }
        if (a.getRequesterId().equals(ctx.userId())) {
            throw ApiException.forbidden("APPROVAL_SELF_APPROVAL",
                    "Separation of duties: requester cannot approve their own request");
        }
        if (Instant.now().isAfter(a.getExpiresAt())) {
            a.setStatus("EXPIRED");
            approvals.save(a);
            throw ApiException.conflict("APPROVAL_EXPIRED", "Approval expired before review");
        }
        a.setApproverId(ctx.userId());
        a.setStatus("APPROVED");
        return approvals.save(a);
    }

    @Transactional
    public Approval reject(TenantContext ctx, UUID approvalId) {
        Approval a = loadForUpdateForTenant(ctx, approvalId);
        if (!ctx.hasAnyRole("APPROVER", "ADMIN")) {
            throw ApiException.forbidden("APPROVAL_REJECT_DENIED", "Role may not reject access changes");
        }
        if (!a.getStatus().equals("PENDING")) {
            throw ApiException.conflict("APPROVAL_NOT_PENDING", "Approval is " + a.getStatus());
        }
        a.setApproverId(ctx.userId());
        a.setStatus("REJECTED");
        return approvals.save(a);
    }

    /** Execute an approved action. Revalidates the fingerprint, expiry, tenant,
     *  and status under a row lock; performs the side effect exactly once. */
    @Transactional
    public AccessGrant execute(TenantContext ctx, UUID approvalId) {
        Approval a = loadForUpdateForTenant(ctx, approvalId);
        if (!a.getStatus().equals("APPROVED")) {
            throw ApiException.conflict("APPROVAL_NOT_APPROVED",
                    "Only APPROVED actions can be executed (current: " + a.getStatus() + ")");
        }
        // Recompute the fingerprint from the STORED proposal and compare (ADR-0005).
        String recomputed = FingerprintService.actionFingerprint(
                a.getToolName(), a.getArgs(), a.getRequesterId(), a.getTenantId(),
                a.getExpiresAt().toString());
        if (!recomputed.equals(a.getActionFingerprint())) {
            throw ApiException.conflict("APPROVAL_TAMPERED",
                    "Action fingerprint mismatch — the proposed action was modified after approval");
        }
        if (Instant.now().isAfter(a.getExpiresAt())) {
            a.setStatus("EXPIRED");
            approvals.save(a);
            throw ApiException.conflict("APPROVAL_EXPIRED", "Approval expired before execution");
        }
        String targetUsername = String.valueOf(a.getArgs().get("targetUsername"));
        String resource = String.valueOf(a.getArgs().get("resource"));
        // Exactly-once: skip insert if the grant already exists (defensive; status gate is primary).
        AccessGrant grant;
        if (grants.existsByTenantIdAndTargetUsernameAndResource(ctx.tenantId(), targetUsername, resource)) {
            grant = grants.findByTenantId(ctx.tenantId()).stream()
                    .filter(g -> g.getTargetUsername().equals(targetUsername) && g.getResource().equals(resource))
                    .findFirst().orElseThrow();
        } else {
            grant = grants.save(new AccessGrant(ctx.tenantId(), targetUsername, resource, a.getApproverId()));
        }
        a.setStatus("EXECUTED");
        a.setExecutedAt(Instant.now());
        approvals.save(a);
        return grant;
    }

    public List<Approval> list(TenantContext ctx, String status) {
        if (status == null || status.isBlank()) return approvals.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId());
        return approvals.findByTenantIdAndStatusOrderByCreatedAtDesc(ctx.tenantId(), status);
    }

    private Approval loadForTenant(TenantContext ctx, UUID approvalId) {
        return approvals.findByIdAndTenantId(approvalId, ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND",
                        "No such approval in your organization"));
    }

    /** Locking read for the approve/execute critical section: the status gate
     *  and the side effect must be atomic under concurrency (exactly-once). */
    private Approval loadForUpdateForTenant(TenantContext ctx, UUID approvalId) {
        return approvals.findByIdAndTenantIdForUpdate(approvalId, ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND",
                        "No such approval in your organization"));
    }

    // Test hook: create an approval with a custom expiry (not exposed via REST).
    @Transactional
    public Approval proposeWithExpiry(TenantContext ctx, String targetUsername, String resource, Instant expiresAt) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("targetUsername", targetUsername);
        args.put("resource", resource);
        Instant exp = expiresAt.truncatedTo(ChronoUnit.MICROS);
        String fingerprint = FingerprintService.actionFingerprint(
                "grant_access", args, ctx.userId(), ctx.tenantId(), exp.toString());
        return approvals.save(new Approval(ctx.tenantId(), "grant_access", args, fingerprint, ctx.userId(), exp));
    }
}
