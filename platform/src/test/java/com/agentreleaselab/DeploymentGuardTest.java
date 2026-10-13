package com.agentreleaselab;

import com.agentreleaselab.config.DeploymentGuard;
import com.agentreleaselab.security.OidcProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deployment mode must fail startup on insecure configuration, and local
 *  mode must stay untouched. Pure unit tests — no Spring context. */
class DeploymentGuardTest {

    private static OidcProperties oidc(boolean enabled, String issuer, String audience, String jwksUri) {
        return new OidcProperties(enabled, issuer, audience, jwksUri,
                "tenant_slug", "role", "scope");
    }

    private static DeploymentGuard guard(String mode, boolean seedEnabled, String seedAdminKey,
                                         OidcProperties oidc, String cors) {
        return new DeploymentGuard(mode, seedEnabled, seedAdminKey, oidc, cors);
    }

    private static DeploymentGuard validDeployment() {
        return guard("deployment", false, "rotated-secret-value",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example");
    }

    @Test
    void localModeNeverFails() {
        // Even the worst local config must not block startup.
        assertThatCode(() -> guard("local", true, "arl-admin-demo-key",
                oidc(false, "", "", ""), "*").validate()).doesNotThrowAnyException();
    }

    @Test
    void deploymentRejectsSeedEnabled() {
        DeploymentGuard g = guard("deployment", true, "rotated-secret-value",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arl.seed-enabled");
    }

    @Test
    void deploymentRejectsDefaultSeedAdminKey() {
        DeploymentGuard g = guard("deployment", false, "arl-admin-demo-key",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arl.seed-admin-api-key");
    }

    @Test
    void deploymentRejectsBlankSeedAdminKey() {
        DeploymentGuard g = guard("deployment", false, "  ",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arl.seed-admin-api-key");
    }

    @Test
    void deploymentRequiresOidc() {
        DeploymentGuard g = guard("deployment", false, "rotated-secret-value",
                oidc(false, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arl.oidc.enabled");
    }

    @Test
    void deploymentRequiresOidcDetails() {
        DeploymentGuard g = guard("deployment", false, "rotated-secret-value",
                oidc(true, "", "", ""),
                "https://dashboard.internal.example");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arl.oidc.issuer");
    }

    @Test
    void deploymentRejectsWildcardCors() {
        DeploymentGuard g = guard("deployment", false, "rotated-secret-value",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://a.example, *");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS");
    }

    @Test
    void failureMessageNeverContainsSecretValues() {
        // The key is the dev default here, so validation fails on another
        // axis; the custom secret must still not leak into the message.
        DeploymentGuard g = guard("deployment", true, "s3cr3t-rotated-value-xyz",
                oidc(false, "", "", ""), "*");
        assertThatThrownBy(g::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("s3cr3t-rotated-value-xyz");
    }

    @Test
    void validDeploymentConfigPasses() {
        assertThatCode(validDeployment()::validate).doesNotThrowAnyException();
    }

    @Test
    void modeMatchingIsCaseInsensitive() {
        assertThatCode(() -> guard("DEPLOYMENT", false, "rotated-secret-value",
                oidc(true, "https://idp.example", "agentrelease-lab", "https://idp.example/jwks"),
                "https://dashboard.internal.example").validate()).doesNotThrowAnyException();
    }
}
