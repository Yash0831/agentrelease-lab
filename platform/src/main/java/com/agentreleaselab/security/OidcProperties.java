package com.agentreleaselab.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OIDC configuration (arl.oidc.*). All values are injectable via
 *  ARL_OIDC_* environment variables. Disabled by default; local/dev keeps
 *  working on API keys alone. */
@Component
public class OidcProperties {

    private final boolean enabled;
    private final String issuer;
    private final String audience;
    private final String jwksUri;
    private final String tenantClaim;
    private final String roleClaim;
    private final String scopeClaim;

    public OidcProperties(
            @Value("${arl.oidc.enabled:false}") boolean enabled,
            @Value("${arl.oidc.issuer:}") String issuer,
            @Value("${arl.oidc.audience:}") String audience,
            @Value("${arl.oidc.jwks-uri:}") String jwksUri,
            @Value("${arl.oidc.tenant-claim:tenant_slug}") String tenantClaim,
            @Value("${arl.oidc.role-claim:role}") String roleClaim,
            @Value("${arl.oidc.scope-claim:scope}") String scopeClaim) {
        this.enabled = enabled;
        this.issuer = issuer == null ? "" : issuer;
        this.audience = audience == null ? "" : audience;
        this.jwksUri = jwksUri == null ? "" : jwksUri;
        this.tenantClaim = tenantClaim == null || tenantClaim.isBlank() ? "tenant_slug" : tenantClaim;
        this.roleClaim = roleClaim == null || roleClaim.isBlank() ? "role" : roleClaim;
        this.scopeClaim = scopeClaim == null || scopeClaim.isBlank() ? "scope" : scopeClaim;
    }

    public boolean isEnabled() { return enabled; }
    public String issuer() { return issuer; }
    public String audience() { return audience; }
    public String jwksUri() { return jwksUri; }
    public String tenantClaim() { return tenantClaim; }
    public String roleClaim() { return roleClaim; }
    public String scopeClaim() { return scopeClaim; }
}
