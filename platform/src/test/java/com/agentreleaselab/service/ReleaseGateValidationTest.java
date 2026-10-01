package com.agentreleaselab.service;

import com.agentreleaselab.domain.EvalRun;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Direct unit tests for release-gate input validation.
 *
 *  Non-finite doubles (NaN/Infinity) cannot be persisted through the JSONB
 *  metrics column, so they are exercised here against the validator itself
 *  rather than through the database. */
class ReleaseGateValidationTest {

    private EvalRun run(Map<String, Object> metrics, String status) {
        EvalRun r = new EvalRun(UUID.randomUUID(), "batch-u", UUID.randomUUID(),
                "ds", "s1", 0, "fixture", Map.of());
        r.setMetrics(metrics);
        r.setStatus(status);
        return r;
    }

    private Map<String, Object> validMetrics() {
        Map<String, Object> m = new HashMap<>();
        m.put("task_completed", true);
        m.put("critical_policy_failure", false);
        m.put("failure_reason", "");
        m.put("latency_ms", 1200.0);
        m.put("estimated_cost_usd", 0.001);
        m.put("evidence_complete", true);
        return m;
    }

    @Test
    void validRunHasNoProblem() {
        assertThat(ReleaseDecisionService.evidenceProblem(
                run(validMetrics(), "SUCCEEDED"))).isNull();
        assertThat(ReleaseDecisionService.evidenceProblem(
                run(validMetrics(), "COMPLETED"))).isNull();
        assertThat(ReleaseDecisionService.evidenceProblem(
                run(validMetrics(), "FAILED"))).isNull();
    }

    @Test
    void nanLatencyIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("latency_ms", Double.NaN);
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("latency_ms").contains("finite");
    }

    @Test
    void infiniteCostIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("estimated_cost_usd", Double.POSITIVE_INFINITY);
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("estimated_cost_usd").contains("finite");
    }

    @Test
    void stringMetricIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("task_completed", "true");
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("task_completed").contains("boolean");
    }

    @Test
    void nullMetricIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("latency_ms", null);
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("latency_ms").contains("missing or null");
    }

    @Test
    void negativeLatencyIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("latency_ms", -1.0);
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("latency_ms").contains(">= 0");
    }

    @Test
    void nonTerminalStatusIsUnusable() {
        assertThat(ReleaseDecisionService.evidenceProblem(
                run(validMetrics(), "RUNNING")))
                .contains("not a recognized terminal status");
        assertThat(ReleaseDecisionService.evidenceProblem(
                run(validMetrics(), "QUEUED")))
                .contains("not a recognized terminal status");
    }

    @Test
    void incompleteEvidenceIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("evidence_complete", false);
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("not marked complete");
    }

    @Test
    void nonBooleanEvidenceCompleteIsUnusable() {
        Map<String, Object> m = validMetrics();
        m.put("evidence_complete", "yes");
        assertThat(ReleaseDecisionService.evidenceProblem(run(m, "SUCCEEDED")))
                .contains("evidence_complete").contains("boolean");
    }

    private Map<String, Object> validThresholds() {
        Map<String, Object> t = new HashMap<>();
        t.put("min_trials_per_scenario", 2);
        t.put("min_task_success_rate", 0.8);
        t.put("max_p95_latency_ms", 30000);
        t.put("max_cost_per_run_usd", 0.50);
        t.put("required_scenarios", List.of("s1"));
        return t;
    }

    @Test
    void validThresholdsPass() {
        ReleaseDecisionService.validateThresholds(validThresholds());
        Map<String, Object> withRegression = validThresholds();
        withRegression.put("max_baseline_regression", 0.1);
        withRegression.put("min_baseline_trials_per_scenario", 2);
        ReleaseDecisionService.validateThresholds(withRegression);
    }

    @Test
    void nanThresholdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("min_task_success_rate", Double.NaN);
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void infiniteThresholdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("max_p95_latency_ms", Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void stringThresholdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("max_cost_per_run_usd", "cheap");
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void fractionalTrialsThresholdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("min_trials_per_scenario", 2.5);
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void zeroBaselineTrialsThresholdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("max_baseline_regression", 0.1);
        t.put("min_baseline_trials_per_scenario", 0);
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void nonStringScenarioIdRejected() {
        Map<String, Object> t = validThresholds();
        t.put("required_scenarios", List.of("s1", 42));
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }

    @Test
    void outOfRangeRegressionRejected() {
        Map<String, Object> t = validThresholds();
        t.put("max_baseline_regression", 1.5);
        assertThatThrownBy(() -> ReleaseDecisionService.validateThresholds(t))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode().equals("INVALID_THRESHOLD"));
    }
}
