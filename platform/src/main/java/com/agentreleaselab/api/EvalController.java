package com.agentreleaselab.api;

import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.security.Authorization;
import com.agentreleaselab.security.OidcAuthService;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.EvalRunService;
import com.agentreleaselab.service.FixtureResetService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Evaluation run records. The worker creates a run, executes the scenario,
 *  then PATCHes metrics back. */
@RestController
@RequestMapping("/api/eval-runs")
public class EvalController {

    private final EvalRunService runs;
    private final FixtureResetService fixtures;
    private final boolean deploymentMode;

    public EvalController(EvalRunService runs, FixtureResetService fixtures,
                          @Value("${arl.mode:local}") String mode) {
        this.runs = runs;
        this.fixtures = fixtures;
        this.deploymentMode = "deployment".equalsIgnoreCase(mode);
    }

    public record CreateRun(UUID agentVersionId, @NotBlank String datasetId, @NotBlank String scenarioId,
                            int trialIndex, @NotBlank String mode, Map<String, Object> chaos, String batchId) {}
    public record FinishRun(@NotBlank String status, Map<String, Object> metrics, String error) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRun body) {
        // The worker (service identity: AGENT api-key user) submits runs;
        // OIDC callers need the arl:evaluate scope or the ADMIN role.
        Authorization.requireScopeOrRole(TenantContext.get(), OidcAuthService.SCOPE_EVALUATE,
                "EVAL_SUBMIT_DENIED", "Evaluation submission requires scope arl:evaluate or an agent/admin role",
                "ADMIN", "AGENT");
        EvalRun r = runs.create(body.agentVersionId(), body.datasetId(), body.scenarioId(),
                body.trialIndex(), body.mode(), body.chaos(), body.batchId());
        return Map.of("id", r.getId().toString(), "status", r.getStatus(), "batchId", r.getBatchId());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> finish(@PathVariable UUID id, @RequestBody FinishRun body) {
        EvalRun r = runs.finish(id, body.status(), body.metrics(), body.error());
        return Map.of("id", r.getId().toString(), "status", r.getStatus());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        EvalRun r = runs.get(id);
        return Map.of("id", r.getId().toString(), "scenarioId", r.getScenarioId(),
                "trialIndex", r.getTrialIndex(), "mode", r.getMode(), "status", r.getStatus(),
                "batchId", r.getBatchId() == null ? "" : r.getBatchId(),
                "metrics", r.getMetrics() == null ? Map.of() : r.getMetrics(),
                "error", r.getError() == null ? "" : r.getError());
    }

    @GetMapping
    public List<Map<String, Object>> listByVersion(@RequestParam UUID agentVersionId) {
        return runs.listByVersion(agentVersionId).stream().map(r -> Map.<String, Object>of(
                "id", r.getId().toString(), "scenarioId", r.getScenarioId(),
                "trialIndex", r.getTrialIndex(), "mode", r.getMode(), "status", r.getStatus(),
                "batchId", r.getBatchId() == null ? "" : r.getBatchId(),
                "metrics", r.getMetrics() == null ? Map.of() : r.getMetrics())).toList();
    }

    /** Reset eval-mutable fixtures (tickets, approvals, grants, service
     *  status) to their seeded state. The worker calls this before every
     *  trial so earlier trials cannot satisfy later ones. Disabled in
     *  deployment mode; otherwise restricted to admins and the worker's
     *  service identity. */
    @PostMapping("/fixtures/reset")
    public Map<String, Object> resetFixtures() {
        if (deploymentMode) {
            throw ApiException.forbidden("FIXTURES_DISABLED",
                    "Fixture reset is disabled in deployment mode");
        }
        Authorization.requireScopeOrRole(TenantContext.get(), OidcAuthService.SCOPE_ADMIN,
                "FIXTURE_RESET_DENIED", "Fixture reset requires scope arl:admin or an admin/agent role",
                "ADMIN", "AGENT");
        return fixtures.reset();
    }
}
