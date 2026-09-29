package com.agentreleaselab.api;

import com.agentreleaselab.domain.ReleasePolicyRepository;
import com.agentreleaselab.service.DatasetService;
import com.agentreleaselab.service.ReleaseDecisionService;
import com.agentreleaselab.domain.ReleaseDecision;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.UUID;

/** Datasets, release policies, and the release decision endpoint. */
@RestController
public class ReleaseApiController {

    private final DatasetService datasets;
    private final ReleaseDecisionService gate;
    private final ReleasePolicyRepository policies;

    public ReleaseApiController(DatasetService datasets, ReleaseDecisionService gate,
                                ReleasePolicyRepository policies) {
        this.datasets = datasets;
        this.gate = gate;
        this.policies = policies;
    }

    @GetMapping("/api/datasets")
    public List<Map<String, Object>> datasets() {
        return datasets.list();
    }

    @GetMapping("/api/datasets/{id}")
    public Map<String, Object> dataset(@PathVariable String id) {
        return datasets.get(id);
    }

    @GetMapping("/api/release-policies")
    public List<Map<String, Object>> policies() {
        return policies.findByTenantIdAndActiveTrueOrderByCreatedAtDesc(TenantContext.get().tenantId()).stream()
                .map(p -> Map.<String, Object>of("id", p.getId().toString(), "name", p.getName(),
                        "thresholds", p.getThresholds())).toList();
    }

    public record CreatePolicy(String name, Map<String, Object> thresholds) {}

    @PostMapping("/api/release-policies")
    public Map<String, Object> createPolicy(@RequestBody CreatePolicy body) {
        UUID tenantId = TenantContext.get().tenantId();
        var existing = policies.findByTenantIdAndName(tenantId, body.name());
        if (existing.isPresent()) {
            return Map.of("id", existing.get().getId().toString(), "name", existing.get().getName(),
                    "thresholds", existing.get().getThresholds(), "reused", true);
        }
        var p = policies.save(new com.agentreleaselab.domain.ReleasePolicy(tenantId, body.name(), body.thresholds()));
        return Map.of("id", p.getId().toString(), "name", p.getName(), "thresholds", p.getThresholds());
    }

    public record EvaluateRequest(UUID candidateVersionId, UUID baselineVersionId,
                                  UUID policyId, String datasetId, String mode) {}

    @PostMapping("/api/release-decisions/evaluate")
    public Map<String, Object> evaluate(@RequestBody EvaluateRequest body) {
        ReleaseDecision d = gate.evaluate(body.candidateVersionId(), body.baselineVersionId(),
                body.policyId(), body.datasetId(), body.mode());
        return Map.of("id", d.getId().toString(), "verdict", d.getVerdict(),
                "evidence", d.getEvidence(), "decidedAt", d.getDecidedAt().toString());
    }

    @GetMapping("/api/release-decisions")
    public List<Map<String, Object>> decisions() {
        return gate.list().stream().map(d -> Map.<String, Object>of(
                "id", d.getId().toString(), "verdict", d.getVerdict(),
                "candidateVersionId", d.getCandidateVersionId().toString(),
                "baselineVersionId", d.getBaselineVersionId().toString(),
                "decidedAt", d.getDecidedAt().toString())).toList();
    }

    @GetMapping("/api/release-decisions/{id}")
    public Map<String, Object> decision(@PathVariable UUID id) {
        ReleaseDecision d = gate.get(id);
        return Map.of("id", d.getId().toString(), "verdict", d.getVerdict(),
                "evidence", d.getEvidence(), "decidedAt", d.getDecidedAt().toString());
    }
}
