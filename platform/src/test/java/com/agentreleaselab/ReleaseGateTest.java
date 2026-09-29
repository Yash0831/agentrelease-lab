package com.agentreleaselab;

import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.EvalRun;
import com.agentreleaselab.domain.ReleaseDecision;
import com.agentreleaselab.domain.ReleasePolicy;
import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.service.EvalRunService;
import com.agentreleaselab.service.FingerprintService;
import com.agentreleaselab.service.ReleaseDecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0007: critical failures block; sparse evidence abstains; thresholds decide. */
@Transactional
class ReleaseGateTest extends ServiceTestBase {

    @Autowired ReleaseDecisionService gate;
    @Autowired EvalRunService runs;
    @Autowired Repositories.AgentVersionRepository versions;
    @Autowired Repositories.ReleasePolicyRepository policies;

    private AgentVersion version(String name) {
        String fp = FingerprintService.agentConfigFingerprint(name, "fixture-1.0",
                Map.of(), Map.of(), "snap", "policy-v1");
        return versions.save(new AgentVersion(name, "prompt", "fixture-1.0",
                Map.of(), Map.of(), "snap", "policy-v1", fp));
    }

    private ReleasePolicy policy(String name, int minTrials, List<String> scenarios) {
        return policies.save(new ReleasePolicy(name, Map.of(
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
        m.put("mode", "fixture");
        return m;
    }

    private void trial(AgentVersion v, String scenario, int idx, boolean completed, boolean critical) {
        EvalRun r = runs.create(v.getId(), "ds-test", scenario, idx, "fixture", Map.of());
        runs.finish(r.getId(), "SUCCEEDED", metrics(completed, critical, 1200), null);
    }

    @Test
    void criticalFailureBlocksRegardlessOfAverages() {
        AgentVersion cand = version("cand-blocked-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-blocked-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, true, true); // critical breach on one trial
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture");
        assertThat(d.getVerdict()).isEqualTo("BLOCKED");
        assertThat(String.valueOf(d.getEvidence().get("blockers"))).containsIgnoringCase("critical");
    }

    @Test
    void sparseEvidenceAbstains() {
        AgentVersion cand = version("cand-sparse-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-sparse-" + UUID.randomUUID(), 3, List.of("s1"));
        trial(cand, "s1", 0, true, false); // only 1 of 3 required trials
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture");
        assertThat(d.getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void cleanStrongCandidatePasses() {
        AgentVersion cand = version("cand-pass-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-pass-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, true, false);
        trial(base, "s1", 0, true, false);
        trial(base, "s1", 1, true, false);
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture");
        assertThat(d.getVerdict()).isEqualTo("PASS");
        assertThat(d.getEvidence()).containsKey("scenario_stats");
    }

    @Test
    void weakCandidateFailsOnSuccessRate() {
        AgentVersion cand = version("cand-fail-" + UUID.randomUUID());
        AgentVersion base = version("base-" + UUID.randomUUID());
        ReleasePolicy p = policy("pol-fail-" + UUID.randomUUID(), 2, List.of("s1"));
        trial(cand, "s1", 0, true, false);
        trial(cand, "s1", 1, false, false); // 50% < 80% threshold
        ReleaseDecision d = gate.evaluate(cand.getId(), base.getId(), p.getId(), "ds-test", "fixture");
        assertThat(d.getVerdict()).isEqualTo("FAIL");
    }
}
