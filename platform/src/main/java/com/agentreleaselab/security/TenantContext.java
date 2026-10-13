package com.agentreleaselab.security;

import java.util.Set;
import java.util.UUID;

/** Request-scoped identity resolved from the API key or an OIDC token.
 *  Tenant id always comes from here — never from model output or request
 *  bodies (ADR-0002). API-key callers carry no scopes; OIDC callers carry
 *  the scopes from the token's scope claim. */
public record TenantContext(UUID tenantId, UUID userId, String username, String role,
                            String tenantSlug, Set<String> scopes, String authType) {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public TenantContext {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        authType = authType == null ? "api-key" : authType;
    }

    public static void set(TenantContext ctx) { CURRENT.set(ctx); }
    public static TenantContext get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public boolean hasAnyRole(String... roles) {
        for (String r : roles) if (role.equals(r)) return true;
        return false;
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** Passes when the caller holds the OIDC scope OR one of the roles.
     *  Scopes are an alternative to roles, never a replacement: existing
     *  role checks keep working unchanged for API-key callers. */
    public boolean hasScopeOrRole(String scope, String... roles) {
        return hasScope(scope) || hasAnyRole(roles);
    }
}
