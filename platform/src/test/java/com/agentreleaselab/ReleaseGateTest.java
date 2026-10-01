package com.agentreleaselab;

import com.agentreleaselab.domain.ReleasePolicyRepository;
import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.ReleaseDecision;
import com.agentreleaselab.domain.ReleasePolicy;
import com.agentreleaselab.service.EvalRunService;
import com.agentreleaselab.service.FingerprintService;
import com.agentreleaselab.service.ReleaseDecisionService;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0007: critical failures block; sparse evidence abstains; thresholds decide. */
@Transactional
class ReleaseGateTest extends ServiceTestBase {

    @Autowired ReleaseDecisionService gate;
    @Autowired EvalRunService runs;
    @Autowired AgentVersionRepository versions;
    @Autowired ReleasePolicyRepository policies;

    private AgentVersion version(String name) {
        UUID tenantId = TenantContext.get().tenantId();
        String fp = FingerprintService.agentConfigFingerprint(name, "fixture-1.0",
                Map.of(), Map.of(), "snap", "policy-v1");
        return versions.save(new AgentVersion(tenantId, name, "prompt", "fixture-1.0",
                Map.of(), Map.of(), "snap", "policy-v1", fp));
    }

    private ReleasePolicy policy(String name, int minTrials, List<String> scenarios) {
        UUID tenantId = TenantContext.get().tenantId();
        return policies.save(new ReleasePolicy(tenantId, name, Map.of(
                "min_trials_per_scenario", minTrials,
                "min_task_success_rate", 0.8,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", scenarios)));
    }

    private Map<String, Object> metrics(boolean completed, boolean critical, double latencyMs) {
        Map<String, Object> m = new HashMap<>();
        m.put("task_completed", completed);
        m.put("critical_policy_failure", critical);
        m.put("failure_reason", critical ? "unauthorized_action_executed" : (completed ? "" : "timeout"));
        m.put("latency_ms", latencyMs);
        m.put("estimated_cost_usd", 0.001);
        m.put("unauthorized_executed", critical ? 1 : 0);
        m.put("evidence_complete", true);
        m.put("mode", "fixture");
        return m;
    }

    private void trial(AgentVersion v, String scenario, int idx, boolean completed, boolean critical) {
        trial(v, scenario, idx, completed, critical, "batch-gate-test");
    }

    private void trial(AgentVersion v, String scenario, int idx, boolean completed, boolean critical, String batchId) {
        EvalRun r = runs.create(v.getId(), "ds-test", scenario, idx, "fixture", Map.of(), batchId);
        runs.finish(r.getId(), "SUCCEEDED", metrics(completed, critical, 1200), null);
    }

    private void trialWithMetrics(AgentVersion v, String scenario, int idx,
                                  Map<String, Object> m, String status, String batchId) {
        EvalRun r = runs.create(v.getId(), "ds-test", scenario, idx, "fixture", Map.of(), batchId);
        runs.finish(r.getId(), status, m, null);
    }

    @Test
    void criticalFailureBlocksRegardlessOfAverages() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-blocked-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-blocked-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, true, true); // critical breach on one trial
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-gate-test");
        assertThat(d.getVerdict()).isEqualTo("BLOCKED");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).containsIgnoringCase("critical");
    }

    @Test
    void sparseEvidenceAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-sparse-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-sparse-" + UUID.randomUUID(), 3, List.of("s1"));
        trial(cand, "s1", 0, true, false); // only 1 of 3 required trials
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-gate-test");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void cleanStrongCandidatePasses() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-pass-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-pass-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, true, false);
        trial(base, "s1", 0, true, false);
        trial(base, "s1", 1, true, false);
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-gate-test");
        assertThat(d.getVerdict()).isEqualTo("PASS");
        assertThat(d.getEvidence()).containsKey("scenario_stats");
    }

    @Test
    void weakCandidateFailsOnSuccessRate() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-fail-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-fail-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, false, false); // 50% < 80% threshold
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-gate-test");
        assertThat(d.getVerdict()).isEqualTo("FAIL");
    }

    @Test
    void batchesAreIsolatedAndRepeatable() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-batch-" + UUID.randomUUID());
        AgentVersion base = version("cand-base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-batch-" + UUID.randomUUID(), 2, List.of("s1"));
        // Same (version, dataset, scenario, trial, mode) in two batches: no
        // uniqueness collision, and each batch is independently inspectable.
        trial(cand, "s1", 0, true, false, "batch-A");
        trial(cand, "s1", 1, true, false, "batch-A");
        trial(base, "s1", 0, true, false, "batch-A");
        trial(base, "s1", 1, true, false, "batch-A");
        trial(cand, "s1", 0, false, false, "batch-B");
        trial(cand, "s1", 1, false, false, "batch-B");
        trial(base, "s1", 0, true, false, "batch-B");
        trial(base, "s1", 1, true, false, "batch-B");
        ReleaseDecision dA = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-A");
        ReleaseDecision dB = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-B");
        assertThat(dA.getVerdict()).isEqualTo("PASS");
        assertThat(dB.getVerdict()).isEqualTo("FAIL");
        assertThat(dA.getBatchId()).isEqualTo("batch-A");
        assertThat(dB.getBatchId()).isEqualTo("batch-B");
        assertThat(dA.getEvidence().get("batch_id")).isEqualTo("batch-A");
    }

    @Test
    void evaluateRejectsMissingBatchId() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-nobatch-" + UUID.randomUUID());
        AgentVersion base = version("cand-nobase-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-nobatch-" + UUID.randomUUID(), 2, List.of("s1"));
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", null))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("BATCH_ID_REQUIRED"));
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "  "))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("BATCH_ID_REQUIRED"));
    }

    @Test
    void emptyRequiredScenariosRejected() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-empty-" + UUID.randomUUID());
        AgentVersion base = version("base-empty-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-empty-" + UUID.randomUUID(), 2, List.of());
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-empty"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("EMPTY_REQUIRED_SCENARIOS"));
    }

    @Test
    void criticalFailureOutsideRequiredScenariosStillBlocks() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-outside-" + UUID.randomUUID());
        AgentVersion base = version("base-outside-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-outside-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false, "batch-outside");
        trial(cand, "s1", 1, true, false, "batch-outside");
        // Critical failure in an OPTIONAL scenario (not in required_scenarios).
        trial(cand, "s-optional", 0, true, true, "batch-outside");
        trial(base, "s1", 0, true, false, "batch-outside");
        trial(base, "s1", 1, true, false, "batch-outside");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-outside");
        assertThat(d.getVerdict()).isEqualTo("BLOCKED");
        assertThat(String.valueOf(d.getEvidence().get("critical_failures"))).contains("s-optional");
    }

    @Test
    void incompleteEvidenceAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-inc-" + UUID.randomUUID());
        AgentVersion base = version("base-inc-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-inc-" + UUID.randomUUID(), 2, List.of("s1"));
        EvalRun r1 = runs.create(cand.getId(), "ds-test", "s1", 0, "fixture", Map.of(), "batch-inc");
        Map<String, Object> incomplete = metrics(true, false, 1200);
        incomplete.put("evidence_complete", false);
        runs.finish(r1.getId(), "SUCCEEDED", incomplete, null);
        EvalRun r2 = runs.create(cand.getId(), "ds-test", "s1", 1, "fixture", Map.of(), "batch-inc");
        runs.finish(r2.getId(), "SUCCEEDED", metrics(true, false, 1200), null);
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-inc");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void baselineRegressionRuleBlocksRelativeDrop() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-reg-" + UUID.randomUUID());
        AgentVersion base = version("base-reg-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        // Candidate passes absolute thresholds (75% < 80%? no — use 100% vs baseline 100%,
        // but with a regression: candidate 2/3 = 66.7%, baseline 3/3 = 100%).
        // Absolute threshold 0.6 passes for candidate, but regression 0.333 > 0.2 blocks.
        ReleasePolicy p = policies.save(new ReleasePolicy(tenantId, "pol-reg-" + UUID.randomUUID(), Map.of(
                "min_trials_per_scenario", 3,
                "min_task_success_rate", 0.6,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", List.of("s1"),
                "max_baseline_regression", 0.2,
                "min_baseline_trials_per_scenario", 2)));
        trial(cand, "s1", 0, true, false, "batch-reg");
        trial(cand, "s1", 1, true, false, "batch-reg");
        trial(cand, "s1", 2, false, false, "batch-reg");
        trial(base, "s1", 0, true, false, "batch-reg");
        trial(base, "s1", 1, true, false, "batch-reg");
        trial(base, "s1", 2, true, false, "batch-reg");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-reg");
        assertThat(d.getVerdict()).isEqualTo("FAIL");
        assertThat(String.valueOf(d.getEvidence().get("relative_failures"))).contains("relative regression");
        assertThat(String.valueOf(d.getEvidence().get("absolute_failures"))).doesNotContain("s1");
    }

    @Test
    void invalidThresholdsRejected() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-inv-" + UUID.randomUUID());
        AgentVersion base = version("base-inv-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        ReleasePolicy bad = policies.save(new ReleasePolicy(tenantId, "pol-inv-" + UUID.randomUUID(), Map.of(
                "min_trials_per_scenario", 2,
                "min_task_success_rate", 1.5,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", List.of("s1"))));
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), bad.getId(), "ds-test", "fixture", "batch-inv"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void insufficientBaselineEvidenceAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-base-" + UUID.randomUUID());
        AgentVersion base = version("base-base-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        // Regression rule enabled: baseline evidence is mandatory.
        ReleasePolicy p = policies.save(new ReleasePolicy(tenantId, "pol-base-" + UUID.randomUUID(), Map.of(
                "min_trials_per_scenario", 3,
                "min_task_success_rate", 0.8,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", List.of("s1"),
                "max_baseline_regression", 0.1,
                "min_baseline_trials_per_scenario", 2)));
        // Candidate has full evidence; baseline has only 1 trial (needs 2).
        trial(cand, "s1", 0, true, false, "batch-base");
        trial(cand, "s1", 1, true, false, "batch-base");
        trial(cand, "s1", 2, true, false, "batch-base");
        trial(base, "s1", 0, true, false, "batch-base");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-base");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("baseline scenario 's1'");
    }

    @Test
    void nullMetricValueAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-null-" + UUID.randomUUID());
        AgentVersion base = version("base-null-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-null-" + UUID.randomUUID(), 2, List.of("s1"));
        Map<String, Object> bad = metrics(true, false, 1200);
        bad.put("latency_ms", null); // null is not a measurement
        trialWithMetrics(cand, "s1", 0, bad, "SUCCEEDED", "batch-null");
        trialWithMetrics(cand, "s1", 1, metrics(true, false, 1200), "SUCCEEDED", "batch-null");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-null");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("latency_ms");
    }

    @Test
    void wrongTypeMetricAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-type-" + UUID.randomUUID());
        AgentVersion base = version("base-type-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-type-" + UUID.randomUUID(), 2, List.of("s1"));
        Map<String, Object> bad = metrics(true, false, 1200);
        bad.put("task_completed", "true"); // String is not a boolean
        trialWithMetrics(cand, "s1", 0, bad, "SUCCEEDED", "batch-type");
        trialWithMetrics(cand, "s1", 1, metrics(true, false, 1200), "SUCCEEDED", "batch-type");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-type");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("must be a boolean");
    }

    @Test
    void negativeLatencyAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-neg-" + UUID.randomUUID());
        AgentVersion base = version("base-neg-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-neg-" + UUID.randomUUID(), 2, List.of("s1"));
        Map<String, Object> bad = metrics(true, false, 1200);
        bad.put("latency_ms", -5); // latency cannot be negative
        trialWithMetrics(cand, "s1", 0, bad, "SUCCEEDED", "batch-neg");
        trialWithMetrics(cand, "s1", 1, metrics(true, false, 1200), "SUCCEEDED", "batch-neg");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-neg");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("latency_ms");
    }

    @Test
    void nonTerminalCandidateRunAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-nonterm-" + UUID.randomUUID());
        AgentVersion base = version("base-nonterm-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-nonterm-" + UUID.randomUUID(), 2, List.of("s1"));
        trialWithMetrics(cand, "s1", 0, metrics(true, false, 1200), "RUNNING", "batch-nonterm");
        trialWithMetrics(cand, "s1", 1, metrics(true, false, 1200), "SUCCEEDED", "batch-nonterm");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-nonterm");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("not a recognized terminal status");
    }

    @Test
    void invalidBaselineEvidenceAbstains() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-binv-" + UUID.randomUUID());
        AgentVersion base = version("base-binv-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        ReleasePolicy p = policies.save(new ReleasePolicy(tenantId, "pol-binv-" + UUID.randomUUID(), Map.of(
                "min_trials_per_scenario", 2,
                "min_task_success_rate", 0.8,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", List.of("s1"),
                "max_baseline_regression", 0.1,
                "min_baseline_trials_per_scenario", 2)));
        trial(cand, "s1", 0, true, false, "batch-binv");
        trial(cand, "s1", 1, true, false, "batch-binv");
        Map<String, Object> bad = metrics(true, false, 1200);
        bad.put("estimated_cost_usd", "cheap"); // wrong type in baseline evidence
        trialWithMetrics(base, "s1", 0, bad, "SUCCEEDED", "batch-binv");
        trialWithMetrics(base, "s1", 1, metrics(true, false, 1200), "SUCCEEDED", "batch-binv");
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture", "batch-binv");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).contains("baseline scenario 's1'");
    }

    @Test
    void stringThresholdRejected() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-sthr-" + UUID.randomUUID());
        AgentVersion base = version("base-sthr-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("min_trials_per_scenario", 2);
        thresholds.put("min_task_success_rate", "high"); // not a number
        thresholds.put("max_p95_latency_ms", 30000);
        thresholds.put("max_cost_per_run_usd", 0.50);
        thresholds.put("required_scenarios", List.of("s1"));
        ReleasePolicy bad = policies.save(new ReleasePolicy(tenantId, "pol-sthr-" + UUID.randomUUID(), thresholds));
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), bad.getId(), "ds-test", "fixture", "batch-sthr"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void nonIntegerTrialsThresholdRejected() {
        asUser(ACME_AGENT);
        AgentVersion cand = version("cand-ithr-" + UUID.randomUUID());
        AgentVersion base = version("base-ithr-" + UUID.randomUUID());
        UUID tenantId = TenantContext.get().tenantId();
        ReleasePolicy bad = policies.save(new ReleasePolicy(tenantId, "pol-ithr-" + UUID.randomUUID(), Map.of(
                "min_trials_per_scenario", 2.5, // not an integer
                "min_task_success_rate", 0.8,
                "max_p95_latency_ms", 30000,
                "max_cost_per_run_usd", 0.50,
                "required_scenarios", List.of("s1"))));
        assertThatThrownBy(() -> gate.evaluate(cand.getId(), base.getId(), bad.getId(), "ds-test", "fixture", "batch-ithr"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }
}
