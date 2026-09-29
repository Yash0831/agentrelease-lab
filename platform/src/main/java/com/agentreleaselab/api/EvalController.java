package com.agentreleaselab.api;

import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.service.EvalRunService;
import jakarta.validation.constraints.NotBlank;
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

    public EvalController(EvalRunService runs) {
        this.runs = runs;
    }

    public record CreateRun(UUID agentVersionId, @NotBlank String datasetId, @NotBlank String scenarioId,
                            int trialIndex, @NotBlank String mode, Map<String, Object> chaos) {}
    public record FinishRun(@NotBlank String status, Map<String, Object> metrics, String error) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRun body) {
        EvalRun r = runs.create(body.agentVersionId(), body.datasetId(), body.scenarioId(),
                body.trialIndex(), body.mode(), body.chaos());
        return Map.of("id", r.getId().toString(), "status", r.getStatus());
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
                "metrics", r.getMetrics() == null ? Map.of() : r.getMetrics(),
                "error", r.getError() == null ? "" : r.getError());
    }

    @GetMapping
    public List<Map<String, Object>> listByVersion(@RequestParam UUID agentVersionId) {
        return runs.listByVersion(agentVersionId).stream().map(r -> Map.<String, Object>of(
                "id", r.getId().toString(), "scenarioId", r.getScenarioId(),
                "trialIndex", r.getTrialIndex(), "mode", r.getMode(), "status", r.getStatus(),
                "metrics", r.getMetrics() == null ? Map.of() : r.getMetrics())).toList();
    }
}
