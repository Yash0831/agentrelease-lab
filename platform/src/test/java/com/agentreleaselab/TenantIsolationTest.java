package com.agentreleaselab;

import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.TicketRepository;
import com.agentreleaselab.domain.RunbookRepository;
import com.agentreleaselab.domain.ApprovalRepository;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.EvalRunService;
import com.agentreleaselab.service.FingerprintService;
import com.agentreleaselab.service.ReleaseDecisionService;
import com.agentreleaselab.service.RetrievalService;
import com.agentreleaselab.service.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 2: one organization cannot retrieve another's documents or records. */
@Transactional
class TenantIsolationTest extends ServiceTestBase {

    @Autowired RetrievalService retrieval;
    @Autowired TicketRepository tickets;
    @Autowired RunbookRepository runbooks;
    @Autowired ApprovalRepository approvals;
    @Autowired EvalRunService evalRuns;
    @Autowired AgentVersionRepository versions;
    @Autowired TraceService traces;
    @Autowired ReleaseDecisionService decisions;

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

    /** Evaluation records are tenant-scoped: a run created by acme is
     *  invisible (not forbidden — nonexistent) to globex, including its
     *  trace timeline and any release decision built on it. */
    @Test
    void crossTenantEvalRunIsNotFound() {
        asUser(ACME_AGENT);
        UUID tenantId = TenantContext.get().tenantId();
        String fp = FingerprintService.agentConfigFingerprint("x-tenant-eval", "fixture-1.0",
                Map.of(), Map.of(), "snap", "policy-v1");
        AgentVersion v = versions.save(new AgentVersion(tenantId, "x-tenant-eval-" + UUID.randomUUID(),
                "prompt", "fixture-1.0", Map.of(), Map.of(), "snap", "policy-v1", fp));
        EvalRun run = evalRuns.create(v.getId(), "ds-test", "s1", 0, "fixture", Map.of(), "batch-tenant-test");
        UUID runId = run.getId();
        traces.record(runId, "tr", "sp", null, "test", "probe", Map.of());

        asUser(GLOBEX_AGENT);
        assertThatThrownBy(() -> evalRuns.get(runId))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("EVAL_RUN_NOT_FOUND"));
        assertThatThrownBy(() -> traces.timeline(runId))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("EVAL_RUN_NOT_FOUND"));
        // Cross-tenant version ids are also unusable: globex cannot evaluate
        // a decision on acme's version.
        assertThatThrownBy(() -> decisions.evaluate(v.getId(), v.getId(), UUID.randomUUID(), "ds-test", "fixture", "batch-tenant-test"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("VERSION_NOT_FOUND"));
    }
}
