package com.agentreleaselab;

import com.agentreleaselab.domain.AccessGrantRepository;
import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.domain.ApprovalRepository;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApprovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the approval fingerprint round-trip bug: the action
 * fingerprint is computed over {@code expiresAt.toString()}, and PostgreSQL
 * {@code timestamptz} cannot store nanoseconds. If propose binds the
 * fingerprint to a nanosecond Instant, the execute-time recompute (from the
 * reloaded row) never matches and every real approval fails with
 * APPROVAL_TAMPERED. The {@link ApprovalWorkflowTest} suite is
 * {@code @Transactional} at class level, so its entities never round-trip
 * through the database and cannot catch this. This test deliberately runs
 * WITHOUT a surrounding transaction so each service call commits and the
 * execute step reloads the row from PostgreSQL.
 */
class ApprovalRoundTripTest extends ServiceTestBase {

    @Autowired ApprovalService approvals;
    @Autowired ApprovalRepository approvalRepo;
    @Autowired AccessGrantRepository grants;

    private UUID createdApprovalId;
    private UUID tenantId;

    @AfterEach
    void cleanupCommittedRows() {
        if (createdApprovalId != null) {
            approvalRepo.deleteById(createdApprovalId);
        }
        if (tenantId != null) {
            grants.findByTenantId(tenantId).stream()
                    .filter(g -> g.getTargetUsername().equals("dave-newhire")
                            && g.getResource().equals("vpn-access"))
                    .forEach(g -> grants.deleteById(g.getId()));
        }
        createdApprovalId = null;
        tenantId = null;
    }

    @Test
    void fingerprintSurvivesRealDatabaseRoundTrip() {
        // Each service call runs in its own transaction and COMMITS
        // (no test-level @Transactional), so execute() reloads the row.
        TenantContext agent = asUser(ACME_AGENT);
        tenantId = agent.tenantId();
        Approval a = approvals.propose(agent, "dave-newhire", "vpn-access", "fingerprint round-trip regression check", "ACME-101");
        createdApprovalId = a.getId();
        UUID approvalId = a.getId();
        TenantContext.clear();

        asUser(ACME_APPROVER);
        approvals.approve(TenantContext.get(), approvalId);
        TenantContext.clear();

        asUser(ACME_AGENT);
        var grant = approvals.execute(TenantContext.get(), approvalId);

        assertThat(grant.getTargetUsername()).isEqualTo("dave-newhire");
        assertThat(grant.getResource()).isEqualTo("vpn-access");
        assertThat(approvalRepo.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo("EXECUTED");

        // Second execution is rejected: exactly-once, no duplicate side effect.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvals.execute(TenantContext.get(), approvalId))
                .isInstanceOf(com.agentreleaselab.service.ApiException.class);
        assertThat(grants.findByTenantId(tenantId).stream()
                .filter(g -> g.getTargetUsername().equals("dave-newhire")
                        && g.getResource().equals("vpn-access"))
                .count()).isEqualTo(1);
    }
}
