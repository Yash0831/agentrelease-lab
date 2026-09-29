package com.agentreleaselab.service;

import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.Repositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CRUD for evaluation run records. The worker (or benchmark script) creates
 *  runs, executes them, then PATCHes metrics back. */
@Service
public class EvalRunService {

    private final Repositories.EvalRunRepository evalRuns;
    private final Repositories.AgentVersionRepository versions;

    public EvalRunService(Repositories.EvalRunRepository evalRuns, Repositories.AgentVersionRepository versions) {
        this.evalRuns = evalRuns;
        this.versions = versions;
    }

    @Transactional
    public EvalRun create(UUID agentVersionId, String datasetId, String scenarioId,
                          int trialIndex, String mode, Map<String, Object> chaos) {
        versions.findById(agentVersionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such agent version"));
        if (!List.of("fixture", "live", "replay").contains(mode)) {
            throw ApiException.badRequest("INVALID_MODE", "mode must be fixture|live|replay");
        }
        EvalRun run = new EvalRun(agentVersionId, datasetId, scenarioId, trialIndex, mode, chaos);
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        return evalRuns.save(run);
    }

    @Transactional
    public EvalRun finish(UUID id, String status, Map<String, Object> metrics, String error) {
        EvalRun run = evalRuns.findById(id)
                .orElseThrow(() -> ApiException.notFound("EVAL_RUN_NOT_FOUND", "No such eval run"));
        run.setStatus(status);
        run.setMetrics(metrics);
        run.setError(error);
        run.setFinishedAt(Instant.now());
        return evalRuns.save(run);
    }

    public EvalRun get(UUID id) {
        return evalRuns.findById(id)
                .orElseThrow(() -> ApiException.notFound("EVAL_RUN_NOT_FOUND", "No such eval run"));
    }

    public List<EvalRun> listByVersion(UUID agentVersionId) {
        return evalRuns.findByAgentVersionIdOrderByStartedAtDesc(agentVersionId);
    }
}
