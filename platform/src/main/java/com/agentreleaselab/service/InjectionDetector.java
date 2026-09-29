package com.agentreleaselab.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic prompt-injection tripwire for tool arguments (ADR-0002).
 *
 * <p>This is a heuristic safety net, not a proof of safety: it blocks tool
 * calls whose arguments echo known injection markers (e.g. content copied
 * verbatim from a compromised retrieved document). The release gate judges
 * the <em>candidate</em> separately on whether it acted on injected content
 * ({@code metrics.critical_policy_failure}), because a blocked attempt still
 * reveals a candidate that follows injected instructions.
 */
public final class InjectionDetector {

    /** Lowercase marker phrases that indicate echoed injected content. */
    private static final List<String> MARKERS = List.of(
            "injected",
            "ignore previous instructions",
            "ignore all previous instructions",
            "disregard previous instructions",
            "disregard all previous instructions",
            "system prompt override",
            "jailbreak");

    private InjectionDetector() {}

    /** Returns the first marker found in the stringified args, or null. */
    public static String findMarker(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Object v : args.values()) {
            if (v != null) sb.append(String.valueOf(v)).append('\n');
        }
        String haystack = sb.toString().toLowerCase(Locale.ROOT);
        for (String m : MARKERS) {
            if (haystack.contains(m)) return m;
        }
        return null;
    }
}
