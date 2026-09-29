package com.agentreleaselab.service;

import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.ReleaseDecision;
import com.agentreleaselab.domain.ReleasePolicy;
import com.agentreleaselab.domain.Repositories;
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

    private final Repositories.EvalRunRepository evalRuns;
    private final Repositories.AgentVersionRepository versions;
    private final Repositories.ReleasePolicyRepository policies;
    private final Repositories.ReleaseDecisionRepository decisions;

    public ReleaseDecisionService(Repositories.EvalRunRepository evalRuns,
                                  Repositories.AgentVersionRepository versions,
                                  Repositories.ReleasePolicyRepository policies,
                                  Repositories.ReleaseDecisionRepository decisions) {
        this.evalRuns = evalRuns;
        this.versions = versions;
        this.policies = policies;
        this.decisions = decisions;
    }

    @Transactional
    public ReleaseDecision evaluate(UUID candidateId, UUID baselineId, UUID policyId,
                                    String datasetId, String mode) {
        AgentVersion candidate = versions.findById(candidateId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such candidate version"));
        AgentVersion baseline = versions.findById(baselineId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such baseline version"));
        ReleasePolicy policy = policies.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "No such release policy"));
        Map<String, Object> t = policy.getThresholds();

        int minTrials = intVal(t, "min_trials_per_scenario", 3);
        double minSuccess = doubleVal(t, "min_task_success_rate", 0.8);
        double maxP95 = doubleVal(t, "max_p95_latency_ms", 30000);
        double maxCost = doubleVal(t, "max_cost_per_run_usd", 0.50);
        @SuppressWarnings("unchecked")
        List<String> requiredScenarios = (List<String>) t.getOrDefault("required_scenarios", List.of());

        List<EvalRun> candRuns = evalRuns
                .findByAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(candidateId, datasetId, mode);
        List<EvalRun> baseRuns = evalRuns
                .findByAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(baselineId, datasetId, mode);

        Map<String, List<EvalRun>> byScenario = groupBy(candRuns);
        List<String> evidenceProblems = new ArrayList<>();
        List<Map<String, Object>> criticalFailures = new ArrayList<>();
        Map<String, Map<String, Object>> scenarioStats = new LinkedHashMap<>();

        for (String scenario : requiredScenarios) {
            List<EvalRun> trials = byScenario.getOrDefault(scenario, List.of());
            List<EvalRun> finished = trials.stream()
                    .filter(r -> r.getMetrics() != null).toList();
            if (finished.size() < minTrials) {
                evidenceProblems.add("scenario '" + scenario + "': " + finished.size()
                        + " finished trials, need >= " + minTrials);
            }
            long completed = finished.stream().filter(r -> boolMetric(r, "task_completed")).count();
            double successRate = finished.isEmpty() ? 0 : (double) completed / finished.size();
            List<Double> latencies = finished.stream().map(r -> doubleMetric(r, "latency_ms")).sorted().toList();
            double p95 = latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1);
            double meanCost = finished.stream().mapToDouble(r -> doubleMetric(r, "estimated_cost_usd")).average().orElse(0);
            for (EvalRun r : finished) {
                if (boolMetric(r, "critical_policy_failure")) {
                    criticalFailures.add(Map.of(
                            "scenario", scenario, "trial", r.getTrialIndex(), "eval_run_id", r.getId().toString(),
                            "failure_reason", strMetric(r, "failure_reason")));
                }
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("trials", finished.size());
            stats.put("task_success_rate", round(successRate));
            stats.put("p95_latency_ms", round(p95));
            stats.put("mean_cost_usd", round(meanCost));
            stats.put("failures", finished.stream().filter(r -> !boolMetric(r, "task_completed"))
                    .map(r -> Map.of("trial", r.getTrialIndex(), "reason", strMetric(r, "failure_reason"))).toList());
            scenarioStats.put(scenario, stats);
        }

        // Baseline comparison (informational; the gate is absolute, not relative).
        Map<String, Double> baselineSuccess = new LinkedHashMap<>();
        for (var e : groupBy(baseRuns).entrySet()) {
            List<EvalRun> f = e.getValue().stream().filter(r -> r.getMetrics() != null).toList();
            double s = f.isEmpty() ? 0 : (double) f.stream().filter(r -> boolMetric(r, "task_completed")).count() / f.size();
            baselineSuccess.put(e.getKey(), round(s));
        }

        String verdict;
        List<String> blockers = new ArrayList<>();
        if (!criticalFailures.isEmpty()) {
            verdict = "BLOCKED";
            blockers.add(criticalFailures.size() + " critical policy failure(s) — security failures block independently of averages");
        } else if (!evidenceProblems.isEmpty()) {
            verdict = "INSUFFICIENT_EVIDENCE";
            blockers.addAll(evidenceProblems);
        } else {
            List<String> fails = new ArrayList<>();
            for (var e : scenarioStats.entrySet()) {
                Map<String, Object> s = e.getValue();
                if ((double) s.get("task_success_rate") < minSuccess)
                    fails.add(e.getKey() + ": success rate " + s.get("task_success_rate") + " < " + minSuccess);
                if ((double) s.get("p95_latency_ms") > maxP95)
                    fails.add(e.getKey() + ": p95 latency " + s.get("p95_latency_ms") + "ms > " + maxP95 + "ms");
                if ((double) s.get("mean_cost_usd") > maxCost)
                    fails.add(e.getKey() + ": mean cost $" + s.get("mean_cost_usd") + " > $" + maxCost);
            }
            if (fails.isEmpty()) { verdict = "PASS"; }
            else { verdict = "FAIL"; blockers.addAll(fails); }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("candidate", Map.of("id", candidate.getId().toString(), "name", candidate.getName(),
                "fingerprint", candidate.getFingerprint(), "model_id", candidate.getModelId()));
        evidence.put("baseline", Map.of("id", baseline.getId().toString(), "name", baseline.getName(),
                "fingerprint", baseline.getFingerprint(), "model_id", baseline.getModelId()));
        evidence.put("policy", Map.of("id", policy.getId().toString(), "name", policy.getName()));
        evidence.put("dataset_id", datasetId);
        evidence.put("mode", mode);
        evidence.put("verdict", verdict);
        evidence.put("blockers", blockers);
        evidence.put("critical_failures", criticalFailures);
        evidence.put("scenario_stats", scenarioStats);
        evidence.put("baseline_success_rates", baselineSuccess);
        evidence.put("thresholds", t);

        ReleaseDecision decision = new ReleaseDecision(candidateId, baselineId, policyId, verdict, evidence);
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

    public List<ReleaseDecision> list() { return decisions.findAllByOrderByDecidedAtDesc(); }
    public ReleaseDecision get(UUID id) {
        return decisions.findById(id).orElseThrow(() -> ApiException.notFound("DECISION_NOT_FOUND", "No such decision"));
    }
}
