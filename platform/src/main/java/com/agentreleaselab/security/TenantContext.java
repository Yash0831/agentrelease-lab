package com.agentreleaselab.security;

import java.util.UUID;

/** Request-scoped identity resolved from the API key. Tenant id always comes
 *  from here — never from model output or request bodies (ADR-0002). */
public record TenantContext(UUID tenantId, UUID userId, String username, String role, String tenantSlug) {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public static void set(TenantContext ctx) { CURRENT.set(ctx); }
    public static TenantContext get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public boolean hasAnyRole(String... roles) {
        for (String r : roles) if (role.equals(r)) return true;
        return false;
    }
}
