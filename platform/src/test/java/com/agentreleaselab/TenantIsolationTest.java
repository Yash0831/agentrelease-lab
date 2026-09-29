package com.agentreleaselab;

import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 2: one organization cannot retrieve another's documents or records. */
@Transactional
class TenantIsolationTest extends ServiceTestBase {

    @Autowired RetrievalService retrieval;
    @Autowired Repositories.TicketRepository tickets;
    @Autowired Repositories.RunbookRepository runbooks;
    @Autowired Repositories.ApprovalRepository approvals;

    @Test
    void acmeAgentCannotSeeGlobexRunbooks() {
        TenantContext acme = asUser(ACME_AGENT);
        List<RetrievalService.SearchHit> hits = retrieval.search("vpn troubleshooting", 10);
        assertThat(hits).isNotEmpty();
        // Every returned runbook must belong to acme: verify via repository.
        for (var h : hits) {
            var rb = runbooks.findByTenantIdAndSlugAndVersion(acme.tenantId(), h.slug(), h.version());
            assertThat(rb).isPresent();
        }
        // Globex-only content marker must not leak: globex runbook mentions hardware keys.
        assertThat(hits).noneMatch(h -> h.snippet().contains("hardware keys"));
    }

    @Test
    void globexAgentCannotSeeAcmeRunbooks() {
        asUser(GLOBEX_AGENT);
        List<RetrievalService.SearchHit> hits = retrieval.search("vpn troubleshooting", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(h -> h.snippet().contains("Globex") || h.snippet().contains("zero-trust")
                || h.slug().equals("password-reset"));
        assertThat(hits).noneMatch(h -> h.snippet().contains("acme-vpn"));
    }

    @Test
    void crossTenantTicketAccessIsNotFound() {
        asUser(ACME_AGENT);
        // GLBX-201 belongs to globex; from acme it must look nonexistent (not forbidden —
        // we must not confirm or deny cross-tenant objects).
        assertThatThrownBy(() -> tickets.findByTenantIdAndTicketKey(
                        TenantContext.get().tenantId(), "GLBX-201")
                        .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "x")))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("TICKET_NOT_FOUND"));
        assertThat(tickets.findByTenantIdOrderByCreatedAtDesc(TenantContext.get().tenantId()))
                .allMatch(t -> t.getTicketKey().startsWith("ACME-"));
    }

    @Test
    void crossTenantRunbookVersionIsNotFound() {
        asUser(GLOBEX_AGENT);
        // "legacy-vpn-notes" exists only in acme.
        assertThatThrownBy(() -> retrieval.getVersion("legacy-vpn-notes", 1))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("RUNBOOK_NOT_FOUND"));
    }

    @Test
    void invalidApiKeyIsRejected() {
        assertThatThrownBy(() -> authService.authenticate("bogus-key"))
                .matches(e -> e instanceof org.springframework.web.server.ResponseStatusException);
    }
}
