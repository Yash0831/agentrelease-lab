package com.agentreleaselab.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable fingerprints for agent configs (requirement 1) and approval actions
 *  (ADR-0005). Canonical JSON (sorted keys) -> SHA-256 hex. */
public final class FingerprintService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FingerprintService() {}

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical JSON with sorted keys so semantically-equal configs hash equal. */
    public static String canonicalJson(Object value) {
        try {
            return MAPPER.writeValueAsString(sort(value));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot canonicalize JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sort(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), sort(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(i -> out.add(sort(i)));
            return out;
        }
        return v;
    }

    /** Agent config fingerprint: prompt + model + retrieval cfg + tool schemas + doc snapshot + policy version. */
    public static String agentConfigFingerprint(String prompt, String modelId,
                                                Map<String, Object> retrievalConfig,
                                                Map<String, Object> toolSchemas,
                                                String docSnapshotId, String policyVersion) {
        Map<String, Object> cfg = new TreeMap<>();
        cfg.put("prompt", prompt);
        cfg.put("model_id", modelId);
        cfg.put("retrieval_config", retrievalConfig);
        cfg.put("tool_schemas", toolSchemas);
        cfg.put("doc_snapshot_id", docSnapshotId);
        cfg.put("policy_version", policyVersion);
        return sha256Hex(canonicalJson(cfg));
    }

    /** Approval action fingerprint: exact tool + args + requester + tenant + expiry (ADR-0005). */
    public static String actionFingerprint(String toolName, Map<String, Object> args,
                                           UUID requesterId, UUID tenantId, String expiresAtIso) {
        Map<String, Object> a = new TreeMap<>();
        a.put("tool", toolName);
        a.put("args", args);
        a.put("requester_id", requesterId.toString());
        a.put("tenant_id", tenantId.toString());
        a.put("expires_at", expiresAtIso);
        return sha256Hex(canonicalJson(a));
    }
}
