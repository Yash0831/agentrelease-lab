package com.agentreleaselab.service;

import com.agentreleaselab.domain.EvalRunRepository;
import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CRUD for evaluation run records. The worker (or benchmark script) creates
 *  runs, executes them, then PATCHes metrics back. All access is
 *  tenant-scoped: one organization can never read another's runs. */
@Service
public class EvalRunService {

    private final EvalRunRepository evalRuns;
    private final AgentVersionRepository versions;

    public EvalRunService(EvalRunRepository evalRuns, AgentVersionRepository versions) {
        this.evalRuns = evalRuns;
        this.versions = versions;
    }

    @Transactional
    public EvalRun create(UUID agentVersionId, String datasetId, String scenarioId,
                          int trialIndex, String mode, Map<String, Object> chaos, String batchId) {
        UUID tenantId = TenantContext.get().tenantId();
        versions.findByIdAndTenantId(agentVersionId, tenantId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such agent version in your organization"));
        if (!List.of("fixture", "live", "replay").contains(mode)) {
            throw ApiException.badRequest("INVALID_MODE", "mode must be fixture|live|replay");
        }
        String batch = (batchId == null || batchId.isBlank()) ? "batch-" + UUID.randomUUID().toString().substring(0, 8) : batchId;
        EvalRun run = new EvalRun(tenantId, batch, agentVersionId, datasetId, scenarioId, trialIndex, mode, chaos);
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        return evalRuns.save(run);
    }

    @Transactional
    public EvalRun finish(UUID id, String status, Map<String, Object> metrics, String error) {
        EvalRun run = get(id);
        run.setStatus(status);
        run.setMetrics(metrics);
        run.setError(error);
        run.setFinishedAt(Instant.now());
        return evalRuns.save(run);
    }

    public EvalRun get(UUID id) {
        return evalRuns.findByIdAndTenantId(id, TenantContext.get().tenantId())
                .orElseThrow(() -> ApiException.notFound("EVAL_RUN_NOT_FOUND", "No such eval run in your organization"));
    }

    public List<EvalRun> listByVersion(UUID agentVersionId) {
        UUID tenantId = TenantContext.get().tenantId();
        versions.findByIdAndTenantId(agentVersionId, tenantId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such agent version in your organization"));
        return evalRuns.findByTenantIdAndAgentVersionIdOrderByStartedAtDesc(tenantId, agentVersionId);
    }
}
