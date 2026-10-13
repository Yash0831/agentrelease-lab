package com.agentreleaselab;

import com.agentreleaselab.domain.Approval;
import com.agentreleaselab.domain.ApprovalRepository;
import com.agentreleaselab.security.AuthService;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OIDC authentication against a stub identity provider: an RSA keypair is
 *  generated in-test and the JWKS is served by a tiny local HTTP server, so
 *  no external IdP is needed. Covers token validation, tenant mapping,
 *  cross-tenant isolation, and scope/role enforcement at the HTTP layer.
 *
 *  Not @Transactional: the MockMvc requests run outside the test thread's
 *  transaction, so created approvals are committed and cleaned up manually. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcAuthTest {

    private static final String ISSUER = "https://idp.test.example";
    private static final String AUDIENCE = "agentrelease-lab";
    private static final String KID = "test-key-1";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static HttpServer jwksServer;
    private static int jwksPort;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<UUID> approvalsToDelete = new ArrayList<>();

    @Autowired MockMvc mvc;
    @Autowired AuthService authService;
    @Autowired ApprovalService approvalService;
    @Autowired ApprovalRepository approvalRepository;

    @BeforeAll
    static void startIdp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();

        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("kid", KID);
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("n", b64url(stripLeadingZero(pub.getModulus().toByteArray())));
        jwk.put("e", b64url(stripLeadingZero(pub.getPublicExponent().toByteArray())));
        String body = MAPPER.writeValueAsString(Map.of("keys", List.of(jwk)));

        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks.json", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        jwksServer.start();
        jwksPort = jwksServer.getAddress().getPort();
    }

    @AfterAll
    static void stopIdp() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @DynamicPropertySource
    static void oidcProps(DynamicPropertyRegistry r) {
        r.add("arl.oidc.enabled", () -> "true");
        r.add("arl.oidc.issuer", () -> ISSUER);
        r.add("arl.oidc.audience", () -> AUDIENCE);
        r.add("arl.oidc.jwks-uri", () -> "http://localhost:" + jwksPort + "/jwks.json");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : approvalsToDelete) approvalRepository.deleteById(id);
        approvalsToDelete.clear();
        TenantContext.clear();
    }

    // ---- token helpers ----

    private static byte[] stripLeadingZero(byte[] b) {
        return (b.length > 1 && b[0] == 0) ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sign(String signingInput, PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return b64url(sig.sign());
    }

    /** Build a JWT with the given claims; signed by keyPair unless overridden. */
    private static String token(Map<String, Object> claims, PrivateKey key, String kid, String alg) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("kid", kid);
        header.put("typ", "JWT");
        String h = b64url(MAPPER.writeValueAsBytes(header));
        String p = b64url(MAPPER.writeValueAsBytes(claims));
        return h + "." + p + "." + sign(h + "." + p, key);
    }

    private static Map<String, Object> baseClaims(String tenantSlug, String role, String scope) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sub", "oidc-test-user");
        c.put("iss", ISSUER);
        c.put("aud", AUDIENCE);
        c.put("exp", Instant.now().getEpochSecond() + 600);
        c.put("iat", Instant.now().getEpochSecond());
        c.put("tenant_slug", tenantSlug);
        c.put("role", role);
        if (scope != null) c.put("scope", scope);
        return c;
    }

    private static String token(Map<String, Object> claims) throws Exception {
        return token(claims, keyPair.getPrivate(), KID, "RS256");
    }

    private String bearer(Map<String, Object> claims) throws Exception {
        return "Bearer " + token(claims);
    }

    // ---- tests ----

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/datasets")
                        .header("Authorization", bearer(baseClaims("acme", "APPROVER", "arl:read"))))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/datasets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        Map<String, Object> claims = baseClaims("acme", "APPROVER", "arl:read");
        claims.put("exp", Instant.now().getEpochSecond() - 600);
        mvc.perform(get("/api/datasets").header("Authorization", bearer(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceIsUnauthorized() throws Exception {
        Map<String, Object> claims = baseClaims("acme", "APPROVER", "arl:read");
        claims.put("aud", "someone-else");
        mvc.perform(get("/api/datasets").header("Authorization", bearer(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsUnauthorized() throws Exception {
        Map<String, Object> claims = baseClaims("acme", "APPROVER", "arl:read");
        claims.put("iss", "https://evil.example");
        mvc.perform(get("/api/datasets").header("Authorization", bearer(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badSignatureIsUnauthorized() throws Exception {
        String forged = token(baseClaims("acme", "ADMIN", "arl:admin"),
                otherKeyPair.getPrivate(), KID, "RS256");
        mvc.perform(get("/api/datasets").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTenantIsUnauthorized() throws Exception {
        mvc.perform(get("/api/datasets")
                        .header("Authorization", bearer(baseClaims("no-such-tenant", "ADMIN", "arl:admin"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRoleIsUnauthorized() throws Exception {
        mvc.perform(get("/api/datasets")
                        .header("Authorization", bearer(baseClaims("acme", "SUPERUSER", "arl:admin"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossTenantDataStaysIsolated() throws Exception {
        // Token for globex must only ever see globex tickets.
        mvc.perform(get("/api/tickets")
                        .header("Authorization", bearer(baseClaims("globex", "ADMIN", "arl:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ticketKey", everyItem(startsWith("GLBX-"))));
        // Token for acme must only ever see acme tickets.
        mvc.perform(get("/api/tickets")
                        .header("Authorization", bearer(baseClaims("acme", "ADMIN", "arl:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ticketKey", everyItem(startsWith("ACME-"))));
    }

    @Test
    void viewerCannotSubmitEvalRun() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "agentVersionId", UUID.randomUUID().toString(),
                "datasetId", "d", "scenarioId", "s", "mode", "fixture"));
        mvc.perform(post("/api/eval-runs")
                        .header("Authorization", bearer(baseClaims("acme", "VIEWER", "arl:read")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVAL_SUBMIT_DENIED"));
    }

    @Test
    void evaluateScopeGrantsEvalSubmission() throws Exception {
        // A REQUESTER with the arl:evaluate scope passes the gate; the run
        // itself then fails on the bogus version id (404, not 403).
        String body = MAPPER.writeValueAsString(Map.of(
                "agentVersionId", UUID.randomUUID().toString(),
                "datasetId", "d", "scenarioId", "s", "mode", "fixture"));
        mvc.perform(post("/api/eval-runs")
                        .header("Authorization", bearer(baseClaims("acme", "REQUESTER", "arl:evaluate")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void viewerCannotApprove() throws Exception {
        Approval approval = proposeAsAdmin("carol-requester", "crm", "oidc-approve-test");
        mvc.perform(post("/api/approvals/" + approval.getId() + "/approve")
                        .header("Authorization", bearer(baseClaims("acme", "VIEWER", "arl:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_APPROVE_DENIED"));
    }

    @Test
    void approverRoleCanStillApproveViaOidc() throws Exception {
        Approval approval = proposeAsAdmin("carol-requester", "crm", "oidc-approve-ok");
        mvc.perform(post("/api/approvals/" + approval.getId() + "/approve")
                        .header("Authorization", bearer(baseClaims("acme", "APPROVER", "arl:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void apiKeyPathStillWorks() throws Exception {
        mvc.perform(get("/api/datasets").header("X-API-Key", "arl-acme-agent-demo"))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointsStayPublic() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components.database.status").value("up"));
        mvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"));
    }

    private Approval proposeAsAdmin(String targetUsername, String resource, String reason) {
        TenantContext ctx = authService.authenticate("arl-acme-admin-demo");
        TenantContext.set(ctx);
        try {
            Approval a = approvalService.propose(ctx, targetUsername, resource, reason, null);
            approvalsToDelete.add(a.getId());
            return a;
        } finally {
            TenantContext.clear();
        }
    }
}
