package com.agentreleaselab.config;

import com.agentreleaselab.security.OidcProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fail-closed startup validation for deployment mode (ARL_MODE=deployment).
 *  In local mode this is a no-op so existing dev/test behavior is unchanged.
 *  Secret values are never included in failure messages. */
@Component
public class DeploymentGuard {

    /** The insecure default shipped in application.yml / docker-compose.yml. */
    static final String DEV_DEFAULT_SEED_ADMIN_KEY = "arl-admin-demo-key";

    private final String mode;
    private final boolean seedEnabled;
    private final String seedAdminApiKey;
    private final OidcProperties oidc;
    private final String corsAllowedOrigins;

    public DeploymentGuard(
            @Value("${arl.mode:local}") String mode,
            @Value("${arl.seed-enabled:true}") boolean seedEnabled,
            @Value("${arl.seed-admin-api-key:}") String seedAdminApiKey,
            OidcProperties oidc,
            @Value("${ARL_CORS_ALLOWED_ORIGINS:*}") String corsAllowedOrigins) {
        this.mode = mode == null ? "local" : mode;
        this.seedEnabled = seedEnabled;
        this.seedAdminApiKey = seedAdminApiKey == null ? "" : seedAdminApiKey;
        this.oidc = oidc;
        this.corsAllowedOrigins = corsAllowedOrigins == null ? "" : corsAllowedOrigins;
    }

    public boolean isDeployment() {
        return "deployment".equalsIgnoreCase(mode);
    }

    @PostConstruct
    public void validate() {
        if (!isDeployment()) return;
        List<String> problems = new ArrayList<>();
        if (seedEnabled) {
            problems.add("arl.seed-enabled must be false (demo data seeding is disabled in deployment mode)");
        }
        if (seedAdminApiKey.isBlank() || DEV_DEFAULT_SEED_ADMIN_KEY.equals(seedAdminApiKey)) {
            problems.add("arl.seed-admin-api-key must be set to a non-default value");
        }
        if (!oidc.isEnabled()) {
            problems.add("OIDC authentication must be enabled (arl.oidc.enabled=true)");
        }
        if (oidc.issuer().isBlank() || oidc.audience().isBlank() || oidc.jwksUri().isBlank()) {
            problems.add("arl.oidc.issuer, arl.oidc.audience and arl.oidc.jwks-uri must all be configured");
        }
        if (Arrays.stream(corsAllowedOrigins.split(",")).map(String::trim).anyMatch("*"::equals)) {
            problems.add("CORS allowed origins must not contain '*' (set ARL_CORS_ALLOWED_ORIGINS explicitly)");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in deployment mode: " + String.join("; ", problems));
        }
    }
}
