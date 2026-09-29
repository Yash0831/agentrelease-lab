package com.agentreleaselab;

import com.agentreleaselab.service.FingerprintService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 1: stable configuration fingerprints. Pure unit test. */
class FingerprintTest {

    private Map<String, Object> retrievalCfg() {
        return Map.of("top_k", 5, "embedding", "fixture", "min_score", 0.1);
    }

    private Map<String, Object> toolSchemas() {
        return Map.of("search_runbooks", Map.of("required", "query"));
    }

    @Test
    void sameConfigProducesSameFingerprint() {
        String a = FingerprintService.agentConfigFingerprint("prompt v1", "fixture-1.0",
                retrievalCfg(), toolSchemas(), "snap-2026-09-29", "policy-v1");
        String b = FingerprintService.agentConfigFingerprint("prompt v1", "fixture-1.0",
                retrievalCfg(), toolSchemas(), "snap-2026-09-29", "policy-v1");
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64); // SHA-256 hex
    }

    @Test
    void anyConfigChangeChangesFingerprint() {
        String base = FingerprintService.agentConfigFingerprint("prompt v1", "fixture-1.0",
                retrievalCfg(), toolSchemas(), "snap-2026-09-29", "policy-v1");
        assertThat(FingerprintService.agentConfigFingerprint("prompt v2", "fixture-1.0",
                retrievalCfg(), toolSchemas(), "snap-2026-09-29", "policy-v1")).isNotEqualTo(base);
        assertThat(FingerprintService.agentConfigFingerprint("prompt v1", "other-model",
                retrievalCfg(), toolSchemas(), "snap-2026-09-29", "policy-v1")).isNotEqualTo(base);
        assertThat(FingerprintService.agentConfigFingerprint("prompt v1", "fixture-1.0",
                retrievalCfg(), toolSchemas(), "snap-2026-09-30", "policy-v1")).isNotEqualTo(base);
    }

    @Test
    void canonicalJsonIsKeyOrderIndependent() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("b", 1); m1.put("a", 2);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("a", 2); m2.put("b", 1);
        assertThat(FingerprintService.canonicalJson(m1)).isEqualTo(FingerprintService.canonicalJson(m2));
    }

    @Test
    void actionFingerprintBindsAllFields() {
        UUID req = UUID.randomUUID(), tenant = UUID.randomUUID();
        Map<String, Object> args = Map.of("targetUsername", "carol", "resource", "vpn-access");
        String a = FingerprintService.actionFingerprint("grant_access", args, req, tenant, "2026-09-29T15:00:00Z");
        String b = FingerprintService.actionFingerprint("grant_access", args, req, tenant, "2026-09-29T15:00:00Z");
        assertThat(a).isEqualTo(b);
        // Swapping the target user must change the fingerprint (tamper evidence).
        String c = FingerprintService.actionFingerprint("grant_access",
                Map.of("targetUsername", "mallory", "resource", "vpn-access"), req, tenant, "2026-09-29T15:00:00Z");
        assertThat(c).isNotEqualTo(a);
    }
}
