package com.agentreleaselab;

import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0005: propose -> approve -> execute with revalidation at every step. */
@Transactional
class ApprovalWorkflowTest extends ServiceTestBase {

    @Autowired ApprovalService approvals;
    @Autowired Repositories.ApprovalRepository approvalRepo;
    @Autowired Repositories.AccessGrantRepository grants;

    @Test
    void fullWorkflowExecutesExactlyOnce() {
        TenantContext agent = asUser(ACME_AGENT);
        Approval a = approvals.propose(agent, "carol-requester", "vpn-access", "onboarding", "ACME-102");
        assertThat(a.getStatus()).isEqualTo("PENDING");

        // Requester cannot approve their own... here approver is a different user.
        TenantContext approver = asUser(ACME_APPROVER);
        Approval approved = approvals.approve(approver, a.getId());
        assertThat(approved.getStatus()).isEqualTo("APPROVED");

        var grant = approvals.execute(approver, a.getId());
        assertThat(grant.getTargetUsername()).isEqualTo("carol-requester");
        assertThat(grant.getResource()).isEqualTo("vpn-access");
        assertThat(grants.existsByTenantIdAndTargetUsernameAndResource(
                agent.tenantId(), "carol-requester", "vpn-access")).isTrue();

        // Second execution must be rejected — no duplicate side effect.
        assertThatThrownBy(() -> approvals.execute(approver, a.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("APPROVAL_NOT_APPROVED"));
        assertThat(grants.findByTenantId(agent.tenantId()).stream()
                .filter(g -> g.getTargetUsername().equals("carol-requester") && g.getResource().equals("vpn-access"))
                .count()).isEqualTo(1);
    }

    @Test
    void selfApprovalIsForbidden() {
        TenantContext admin = asUser(ACME_ADMIN);
        Approval a = approvals.propose(admin, "carol-requester", "vpn-access", "test", null);
        assertThatThrownBy(() -> approvals.approve(admin, a.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("APPROVAL_SELF_APPROVAL"));
    }

    @Test
    void expiredApprovalCannotBeApprovedOrExecuted() {
        TenantContext agent = asUser(ACME_AGENT);
        Approval a = approvals.proposeWithExpiry(agent, "carol-requester", "vpn-access",
                Instant.now().minus(1, ChronoUnit.HOURS));
        TenantContext approver = asUser(ACME_APPROVER);
        assertThatThrownBy(() -> approvals.approve(approver, a.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("APPROVAL_EXPIRED"));
    }

    @Test
    void tamperedArgsAreDetectedAtExecution() {
        TenantContext agent = asUser(ACME_AGENT);
        Approval a = approvals.propose(agent, "carol-requester", "vpn-access", "test", null);
        asUser(ACME_APPROVER);
        approvals.approve(TenantContext.get(), a.getId());

        // Attacker modifies the stored args after approval (simulated direct tamper).
        Approval stored = approvalRepo.findById(a.getId()).orElseThrow();
        var tampered = new HashMap<>(stored.getArgs());
        tampered.put("targetUsername", "mallory");
        stored.setArgs(tampered);
        approvalRepo.saveAndFlush(stored);

        assertThatThrownBy(() -> approvals.execute(TenantContext.get(), a.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("APPROVAL_TAMPERED"));
        assertThat(grants.existsByTenantIdAndTargetUsernameAndResource(
                agent.tenantId(), "mallory", "vpn-access")).isFalse();
    }

    @Test
    void crossTenantApprovalIsInvisible() {
        TenantContext agent = asUser(ACME_AGENT);
        Approval a = approvals.propose(agent, "carol-requester", "vpn-access", "test", null);
        // Globex admin must not see or touch acme's approval.
        asUser(GLOBEX_ADMIN);
        assertThat(approvals.list(TenantContext.get(), null)).noneMatch(x -> x.getId().equals(a.getId()));
        assertThatThrownBy(() -> approvals.approve(TenantContext.get(), a.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("APPROVAL_NOT_FOUND"));
    }
}
