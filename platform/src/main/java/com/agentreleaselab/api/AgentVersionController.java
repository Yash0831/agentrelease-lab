package com.agentreleaselab.api;

import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.FingerprintService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Agent version registry. Fingerprints are computed server-side so a version's
 *  identity can never be misreported by a client. */
@RestController
@RequestMapping("/api/agent-versions")
public class AgentVersionController {

    private final Repositories.AgentVersionRepository versions;

    public AgentVersionController(Repositories.AgentVersionRepository versions) {
        this.versions = versions;
    }

    public record RegisterVersion(@NotBlank String name, @NotBlank String prompt, @NotBlank String modelId,
                                  Map<String, Object> retrievalConfig, Map<String, Object> toolSchemas,
                                  @NotBlank String docSnapshotId, @NotBlank String policyVersion) {}

    @PostMapping
    public Map<String, Object> register(@RequestBody RegisterVersion body) {
        if (versions.findByName(body.name()).isPresent()) {
            throw ApiException.conflict("VERSION_EXISTS", "An agent version with this name already exists");
        }
        String fingerprint = FingerprintService.agentConfigFingerprint(
                body.prompt(), body.modelId(),
                body.retrievalConfig() == null ? Map.of() : body.retrievalConfig(),
                body.toolSchemas() == null ? Map.of() : body.toolSchemas(),
                body.docSnapshotId(), body.policyVersion());
        AgentVersion v = new AgentVersion(body.name(), body.prompt(), body.modelId(),
                body.retrievalConfig() == null ? Map.of() : body.retrievalConfig(),
                body.toolSchemas() == null ? Map.of() : body.toolSchemas(),
                body.docSnapshotId(), body.policyVersion(), fingerprint);
        v = versions.save(v);
        return Map.of("id", v.getId().toString(), "name", v.getName(), "fingerprint", v.getFingerprint());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return versions.findAll().stream().map(v -> Map.<String, Object>of(
                "id", v.getId().toString(), "name", v.getName(), "modelId", v.getModelId(),
                "fingerprint", v.getFingerprint(), "docSnapshotId", v.getDocSnapshotId(),
                "policyVersion", v.getPolicyVersion())).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable java.util.UUID id) {
        AgentVersion v = versions.findById(id)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "No such agent version"));
        return Map.of("id", v.getId().toString(), "name", v.getName(), "prompt", v.getPrompt(),
                "modelId", v.getModelId(), "retrievalConfig", v.getRetrievalConfig(),
                "toolSchemas", v.getToolSchemas(), "docSnapshotId", v.getDocSnapshotId(),
                "policyVersion", v.getPolicyVersion(), "fingerprint", v.getFingerprint());
    }
}
