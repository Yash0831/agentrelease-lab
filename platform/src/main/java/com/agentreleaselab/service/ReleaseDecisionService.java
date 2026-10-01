package com.agentreleaselab.service;

import com.agentreleaselab.domain.ReleasePolicyRepository;
import com.agentreleaselab.domain.ReleaseDecisionRepository;
import com.agentreleaselab.domain.EvalRunRepository;
import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.ReleaseDecision;
import com.agentreleaselab.domain.ReleasePolicy;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Release gate engine (ADR-0007):
 *  1. Any critical policy failure -> BLOCKED (no averaging away security).
 *  2. Too few trials / missing scenarios -> INSUFFICIENT_EVIDENCE.
 *  3. Thresholds on success rate, p95 latency, cost -> PASS / FAIL.
 *  Every verdict ships the full evidence bundle. */
@Service
public class ReleaseDecisionService {

    private final EvalRunRepository evalRuns;
    private final AgentVersionRepository versions;
    private final ReleasePolicyRepository policies;
    private final ReleaseDecisionRepository decisions;

    public ReleaseDecisionService(EvalRunRepository evalRuns,
                                  AgentVersionRepository versions,
                                  ReleasePolicyRepository policies,
                                  ReleaseDecisionRepository decisions) {
        this.evalRuns = evalRuns;
        this.versions = versions;
        this.policies = policies;
        this.decisions = decisions;
    }

    @Transactional
    public ReleaseDecision evaluate(UUID candidateId, UUID baselineId, UUID policyId,
                                    String datasetId, String mode, String batchId) {
        UUID tenantId = TenantContext.get().tenantId();
        AgentVersion candidate = versions.findByIdAndTenantId(candidateId, tenantId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such candidate version in your organization"));
        AgentVersion baseline = versions.findByIdAndTenantId(baselineId, tenantId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such baseline version in your organization"));
        ReleasePolicy policy = policies.findByIdAndTenantId(policyId, tenantId)
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "No such release policy in your organization"));
        if (batchId == null || batchId.isBlank()) {
            throw ApiException.badRequest("BATCH_ID_REQUIRED",
                    "Release decisions are scoped to an evaluation batch: pass the batchId the candidate and baseline runs belong to");
        }
        Map<String, Object> t = policy.getThresholds();
        validateThresholds(t);

        int minTrials = intVal(t, "min_trials_per_scenario", 3);
        double minSuccess = doubleVal(t, "min_task_success_rate", 0.8);
        double maxP95 = doubleVal(t, "max_p95_latency_ms", 30000);
        double maxCost = doubleVal(t, "max_cost_per_run_usd", 0.50);
        @SuppressWarnings("unchecked")
        List<String> requiredScenarios = (List<String>) t.getOrDefault("required_scenarios", List.of());
        // Fail closed: a policy with no required scenarios proves nothing.
        if (requiredScenarios.isEmpty()) {
            throw ApiException.badRequest("EMPTY_REQUIRED_SCENARIOS",
                    "Policy '" + policy.getName() + "' has no required_scenarios: a release gate with nothing to check would pass vacuously");
        }
        // Baseline-regression rule (configurable, opt-in).
        double maxRegression = doubleVal(t, "max_baseline_regression", -1);
        int minBaselineTrials = intVal(t, "min_baseline_trials_per_scenario", 2);

        List<EvalRun> candRuns = evalRuns
                .findByTenantIdAndBatchIdAndAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(tenantId, batchId, candidateId, datasetId, mode);
        List<EvalRun> baseRuns = evalRuns
                .findByTenantIdAndBatchIdAndAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(tenantId, batchId, baselineId, datasetId, mode);

        Map<String, List<EvalRun>> byScenario = groupBy(candRuns);
        Map<String, List<EvalRun>> baseByScenario = groupBy(baseRuns);
        List<String> evidenceProblems = new ArrayList<>();
        List<Map<String, Object>> criticalFailures = new ArrayList<>();
        Map<String, Map<String, Object>> scenarioStats = new LinkedHashMap<>();

        // Critical security failures are inspected across EVERY candidate run
        // in the batch — including scenarios outside the required set. A
        // critical failure in an optional scenario still blocks.
        for (EvalRun r : candRuns) {
            if (r.getMetrics() != null && boolMetric(r, "critical_policy_failure")) {
                criticalFailures.add(Map.of(
                        "scenario", r.getScenarioId(), "trial", r.getTrialIndex(),
                        "eval_run_id", r.getId().toString(),
                        "failure_reason", strMetric(r, "failure_reason")));
            }
        }

        for (String scenario : requiredScenarios) {
            List<EvalRun> trials = byScenario.getOrDefault(scenario, List.of());
            List<EvalRun> finished = trials.stream()
                    .filter(r -> r.getMetrics() != null).toList();
            if (finished.size() < minTrials) {
                evidenceProblems.add("scenario '" + scenario + "': " + finished.size()
                        + " finished trials, need >= " + minTrials);
                continue;
            }
            // Mandatory metrics must be present; missing measurements are
            // incomplete evidence, not success.
            List<String> missing = finished.stream()
                    .filter(r -> !hasMandatoryMetrics(r))
                    .map(r -> "trial " + r.getTrialIndex())
                    .toList();
            if (!missing.isEmpty()) {
                evidenceProblems.add("scenario '" + scenario + "': missing mandatory metrics in "
                        + String.join(", ", missing));
                continue;
            }
            List<EvalRun> incomplete = finished.stream()
                    .filter(r -> !boolMetric(r, "evidence_complete", true)).toList();
            if (!incomplete.isEmpty()) {
                evidenceProblems.add("scenario '" + scenario + "': incomplete evidence in "
                        + incomplete.stream().map(r -> "trial " + r.getTrialIndex()).toList());
                continue;
            }
            long completed = finished.stream().filter(r -> boolMetric(r, "task_completed")).count();
            double successRate = (double) completed / finished.size();
            List<Double> latencies = finished.stream().map(r -> doubleMetric(r, "latency_ms")).sorted().toList();
            double p95 = latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1);
            double meanCost = finished.stream().mapToDouble(r -> doubleMetric(r, "estimated_cost_usd")).average().orElse(0);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("trials", finished.size());
            // Store unrounded for threshold comparison; round only for display.
            stats.put("task_success_rate", successRate);
            stats.put("task_success_rate_display", round(successRate));
            stats.put("p95_latency_ms", p95);
            stats.put("p95_latency_ms_display", round(p95));
            stats.put("mean_cost_usd", meanCost);
            stats.put("mean_cost_usd_display", round(meanCost));
            stats.put("failures", finished.stream().filter(r -> !boolMetric(r, "task_completed"))
                    .map(r -> Map.of("trial", r.getTrialIndex(), "reason", strMetric(r, "failure_reason"))).toList());
            scenarioStats.put(scenario, stats);
        }

        // Baseline comparison: informational rates plus the configurable
        // regression rule (relative, distinct from absolute thresholds).
        Map<String, Double> baselineSuccess = new LinkedHashMap<>();
        Map<String, Double> baselineSuccessUnrounded = new LinkedHashMap<>();
        for (var e : baseByScenario.entrySet()) {
            List<EvalRun> f = e.getValue().stream().filter(r -> r.getMetrics() != null).toList();
            double s = f.isEmpty() ? 0 : (double) f.stream().filter(r -> boolMetric(r, "task_completed")).count() / f.size();
            baselineSuccessUnrounded.put(e.getKey(), s);
            baselineSuccess.put(e.getKey(), round(s));
        }

        String verdict;
        List<String> blockers = new ArrayList<>();
        List<String> absoluteFailures = new ArrayList<>();
        List<String> relativeFailures = new ArrayList<>();
        if (!criticalFailures.isEmpty()) {
            verdict = "BLOCKED";
            blockers.add(criticalFailures.size() + " critical policy failure(s) — security failures block independently of averages");
        } else if (!evidenceProblems.isEmpty()) {
            verdict = "INSUFFICIENT_EVIDENCE";
            blockers.addAll(evidenceProblems);
        } else {
            for (var e : scenarioStats.entrySet()) {
                Map<String, Object> s = e.getValue();
                double rate = (double) s.get("task_success_rate");
                double p95 = (double) s.get("p95_latency_ms");
                double cost = (double) s.get("mean_cost_usd");
                if (rate < minSuccess)
                    absoluteFailures.add(e.getKey() + ": absolute success rate " + round(rate) + " < " + minSuccess);
                if (p95 > maxP95)
                    absoluteFailures.add(e.getKey() + ": absolute p95 latency " + round(p95) + "ms > " + maxP95 + "ms");
                if (cost > maxCost)
                    absoluteFailures.add(e.getKey() + ": absolute mean cost $" + round(cost) + " > $" + maxCost);
                // Relative regression rule: only on comparable scenarios with
                // sufficient baseline evidence.
                if (maxRegression >= 0) {
                    List<EvalRun> baseTrials = baseByScenario.getOrDefault(e.getKey(), List.of()).stream()
                            .filter(r -> r.getMetrics() != null).toList();
                    Double baseRate = baselineSuccessUnrounded.get(e.getKey());
                    if (baseTrials.size() >= minBaselineTrials && baseRate != null) {
                        double drop = baseRate - rate;
                        if (drop > maxRegression) {
                            relativeFailures.add(e.getKey() + ": relative regression vs baseline: success rate "
                                    + round(rate) + " is " + round(drop) + " below baseline "
                                    + round(baseRate) + " (allowed drop " + maxRegression + ")");
                        }
                    }
                }
            }
            blockers.addAll(absoluteFailures);
            blockers.addAll(relativeFailures);
            if (blockers.isEmpty()) { verdict = "PASS"; }
            else { verdict = "FAIL"; }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("candidate", Map.of("id", candidate.getId().toString(), "name", candidate.getName(),
                "fingerprint", candidate.getFingerprint(), "model_id", candidate.getModelId()));
        evidence.put("baseline", Map.of("id", baseline.getId().toString(), "name", baseline.getName(),
                "fingerprint", baseline.getFingerprint(), "model_id", baseline.getModelId()));
        evidence.put("policy", Map.of("id", policy.getId().toString(), "name", policy.getName()));
        evidence.put("dataset_id", datasetId);
        evidence.put("mode", mode);
        evidence.put("batch_id", batchId);
        evidence.put("verdict", verdict);
        evidence.put("blockers", blockers);
        evidence.put("absolute_failures", absoluteFailures);
        evidence.put("relative_failures", relativeFailures);
        evidence.put("critical_failures", criticalFailures);
        evidence.put("scenario_stats", scenarioStats);
        evidence.put("baseline_success_rates", baselineSuccess);
        evidence.put("thresholds", t);

        ReleaseDecision decision = new ReleaseDecision(tenantId, batchId, candidateId, baselineId, policyId, verdict, evidence);
        return decisions.save(decision);
    }

    private Map<String, List<EvalRun>> groupBy(List<EvalRun> runs) {
        Map<String, List<EvalRun>> out = new LinkedHashMap<>();
        for (EvalRun r : runs) out.computeIfAbsent(r.getScenarioId(), k -> new ArrayList<>()).add(r);
        return out;
    }

    private boolean boolMetric(EvalRun r, String key) {
        Object v = r.getMetrics().get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private boolean boolMetric(EvalRun r, String key, boolean def) {
        Object v = r.getMetrics().get(key);
        return v == null ? def : (v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v)));
    }

    /** Mandatory measurements every finished trial must carry. Missing
     *  measurements are incomplete evidence, never success. */
    private static final List<String> MANDATORY_METRICS = List.of(
            "task_completed", "critical_policy_failure", "latency_ms",
            "estimated_cost_usd", "evidence_complete");

    private boolean hasMandatoryMetrics(EvalRun r) {
        Map<String, Object> m = r.getMetrics();
        return m != null && MANDATORY_METRICS.stream().allMatch(m::containsKey);
    }

    /** Reject nonsensical threshold configurations fail-fast. */
    private void validateThresholds(Map<String, Object> t) {
        int minTrials = intVal(t, "min_trials_per_scenario", 3);
        double minSuccess = doubleVal(t, "min_task_success_rate", 0.8);
        double maxP95 = doubleVal(t, "max_p95_latency_ms", 30000);
        double maxCost = doubleVal(t, "max_cost_per_run_usd", 0.50);
        if (minTrials < 1)
            throw ApiException.badRequest("INVALID_THRESHOLD", "min_trials_per_scenario must be >= 1");
        if (minSuccess < 0 || minSuccess > 1)
            throw ApiException.badRequest("INVALID_THRESHOLD", "min_task_success_rate must be between 0 and 1");
        if (maxP95 <= 0)
            throw ApiException.badRequest("INVALID_THRESHOLD", "max_p95_latency_ms must be > 0");
        if (maxCost < 0)
            throw ApiException.badRequest("INVALID_THRESHOLD", "max_cost_per_run_usd must be >= 0");
        Object rs = t.get("required_scenarios");
        if (rs != null && !(rs instanceof List))
            throw ApiException.badRequest("INVALID_THRESHOLD", "required_scenarios must be a list of scenario ids");
        double maxReg = doubleVal(t, "max_baseline_regression", -1);
        if (t.containsKey("max_baseline_regression") && (maxReg < 0 || maxReg > 1))
            throw ApiException.badRequest("INVALID_THRESHOLD", "max_baseline_regression must be between 0 and 1");
    }

    private double doubleMetric(EvalRun r, String key) {
        Object v = r.getMetrics().get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private String strMetric(EvalRun r, String key) {
        Object v = r.getMetrics().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private int intVal(Map<String, Object> t, String key, int def) {
        Object v = t.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private double doubleVal(Map<String, Object> t, String key, double def) {
        Object v = t.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    public List<ReleaseDecision> list() {
        return decisions.findByTenantIdOrderByDecidedAtDesc(TenantContext.get().tenantId());
    }
    public ReleaseDecision get(UUID id) {
        return decisions.findByIdAndTenantId(id, TenantContext.get().tenantId())
                .orElseThrow(() -> ApiException.notFound("DECISION_NOT_FOUND", "No such decision in your organization"));
    }
}
