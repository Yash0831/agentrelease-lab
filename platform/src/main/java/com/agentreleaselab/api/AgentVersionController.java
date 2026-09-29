package com.agentreleaselab.api;

import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.FingerprintService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Agent version registry. Fingerprints are computed server-side so a version's
 *  identity can never be misreported by a client. */
@RestController
@RequestMapping("/api/agent-versions")
public class AgentVersionController {

    private final AgentVersionRepository versions;

    public AgentVersionController(AgentVersionRepository versions) {
        this.versions = versions;
    }

    public record RegisterVersion(@NotBlank String name, @NotBlank String prompt, @NotBlank String modelId,
                                  Map<String, Object> retrievalConfig, Map<String, Object> toolSchemas,
                                  @NotBlank String docSnapshotId, @NotBlank String policyVersion) {}

    @PostMapping
    public Map<String, Object> register(@RequestBody RegisterVersion body) {
        UUID tenantId = TenantContext.get().tenantId();
        if (versions.findByTenantIdAndName(tenantId, body.name()).isPresent()) {
            throw ApiException.conflict("VERSION_EXISTS", "An agent version with this name already exists in your organization");
        }
        String fingerprint = FingerprintService.agentConfigFingerprint(
                body.prompt(), body.modelId(),
                body.retrievalConfig() == null ? Map.of() : body.retrievalConfig(),
                body.toolSchemas() == null ? Map.of() : body.toolSchemas(),
                body.docSnapshotId(), body.policyVersion());
        // Content-addressed within the tenant: re-registering identical config reuses the record.
        var existing = versions.findByTenantIdAndFingerprint(tenantId, fingerprint);
        AgentVersion v = existing.orElseGet(() -> versions.save(new AgentVersion(tenantId, body.name(), body.prompt(), body.modelId(),
                body.retrievalConfig() == null ? Map.of() : body.retrievalConfig(),
                body.toolSchemas() == null ? Map.of() : body.toolSchemas(),
                body.docSnapshotId(), body.policyVersion(), fingerprint)));
        return Map.of("id", v.getId().toString(), "name", v.getName(), "fingerprint", v.getFingerprint(),
                "reused", existing.isPresent());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        UUID tenantId = TenantContext.get().tenantId();
        return versions.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(v -> Map.<String, Object>of(
                "id", v.getId().toString(), "name", v.getName(), "modelId", v.getModelId(),
                "fingerprint", v.getFingerprint(), "docSnapshotId", v.getDocSnapshotId(),
                "policyVersion", v.getPolicyVersion())).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable java.util.UUID id) {
        AgentVersion v = versions.findByIdAndTenantId(id, TenantContext.get().tenantId())
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such agent version"));
        return Map.of("id", v.getId().toString(), "name", v.getName(), "prompt", v.getPrompt(),
                "modelId", v.getModelId(), "retrievalConfig", v.getRetrievalConfig(),
                "toolSchemas", v.getToolSchemas(), "docSnapshotId", v.getDocSnapshotId(),
                "policyVersion", v.getPolicyVersion(), "fingerprint", v.getFingerprint());
    }
}
