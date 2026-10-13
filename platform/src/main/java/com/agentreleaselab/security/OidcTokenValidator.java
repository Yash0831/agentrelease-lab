package com.agentreleaselab.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates OIDC ID/access JWTs without extra dependencies: RS256 signature
 *  against the provider JWKS (fetched + cached), exact issuer match, audience
 *  containment, expiry with a 60s clock skew. Returns the claim set on success.
 *  Never logs or returns token material. */
@Component
public class OidcTokenValidator {

    private static final Duration JWKS_TTL = Duration.ofMinutes(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final OidcProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private volatile CachedJwks cache;

    public OidcTokenValidator(OidcProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    private record CachedJwks(Map<String, PublicKey> keys, Instant fetchedAt) {}

    /** Validate the token and return its claims. Throws OidcAuthException on
     *  any failure (malformed, bad signature, wrong iss/aud, expired). */
    public Map<String, Object> validate(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new OidcAuthException("OIDC_TOKEN_MALFORMED", "Token must have three dot-separated segments");
        }
        Map<String, Object> header = decodeJson(parts[0], "OIDC_TOKEN_MALFORMED");
        if (!"RS256".equals(header.get("alg"))) {
            throw new OidcAuthException("OIDC_UNSUPPORTED_ALG", "Only RS256 tokens are accepted");
        }
        String kid = header.get("kid") instanceof String s ? s : null;
        PublicKey key = resolveKey(kid);
        verifySignature(parts[0] + "." + parts[1], parts[2], key);

        Map<String, Object> claims = decodeJson(parts[1], "OIDC_TOKEN_MALFORMED");
        if (!props.issuer().equals(String.valueOf(claims.get("iss")))) {
            throw new OidcAuthException("OIDC_BAD_ISSUER", "Token issuer is not trusted");
        }
        if (!audienceMatches(claims.get("aud"))) {
            throw new OidcAuthException("OIDC_BAD_AUDIENCE", "Token audience does not include this service");
        }
        Object exp = claims.get("exp");
        long expSeconds = exp instanceof Number n ? n.longValue() : -1;
        if (expSeconds < 0 || Instant.now().getEpochSecond() - CLOCK_SKEW_SECONDS > expSeconds) {
            throw new OidcAuthException("OIDC_TOKEN_EXPIRED", "Token is expired");
        }
        return claims;
    }

    private boolean audienceMatches(Object aud) {
        if (aud instanceof String s) return props.audience().equals(s);
        if (aud instanceof List<?> l) return l.contains(props.audience());
        return false;
    }

    private PublicKey resolveKey(String kid) {
        CachedJwks c = cache;
        if (c != null && Duration.between(c.fetchedAt(), Instant.now()).compareTo(JWKS_TTL) < 0) {
            PublicKey k = kid == null ? null : c.keys().get(kid);
            if (k != null) return k;
        }
        // Refresh once (covers key rotation and cold cache), then fail closed.
        CachedJwks fresh = fetchJwks();
        cache = fresh;
        PublicKey k = kid == null ? null : fresh.keys().get(kid);
        if (k == null) {
            throw new OidcAuthException("OIDC_UNKNOWN_KEY", "Token key id is not in the provider JWKS");
        }
        return k;
    }

    private CachedJwks fetchJwks() {
        if (props.jwksUri().isBlank()) {
            throw new OidcAuthException("OIDC_MISCONFIGURED", "OIDC JWKS URI is not configured");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.jwksUri()))
                    .timeout(HTTP_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new OidcAuthException("OIDC_JWKS_UNAVAILABLE",
                        "JWKS endpoint returned HTTP " + resp.statusCode());
            }
            Map<String, Object> doc = mapper.readValue(resp.body(),
                    new TypeReference<Map<String, Object>>() {});
            Object keys = doc.get("keys");
            if (!(keys instanceof List<?> list)) {
                throw new OidcAuthException("OIDC_JWKS_UNAVAILABLE", "JWKS document has no keys array");
            }
            Map<String, PublicKey> out = new ConcurrentHashMap<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "RSA".equals(m.get("kty")) && m.get("kid") instanceof String kid) {
                    out.put(kid, rsaPublicKey(String.valueOf(m.get("n")), String.valueOf(m.get("e"))));
                }
            }
            if (out.isEmpty()) {
                throw new OidcAuthException("OIDC_JWKS_UNAVAILABLE", "JWKS document contains no RSA keys");
            }
            return new CachedJwks(out, Instant.now());
        } catch (OidcAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcAuthException("OIDC_JWKS_UNAVAILABLE", "Could not fetch provider JWKS", e);
        }
    }

    private PublicKey rsaPublicKey(String n, String e) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
        return KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private void verifySignature(String signingInput, String signatureB64, PublicKey key) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(signatureB64))) {
                throw new OidcAuthException("OIDC_BAD_SIGNATURE", "Token signature is invalid");
            }
        } catch (OidcAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcAuthException("OIDC_BAD_SIGNATURE", "Token signature could not be verified", e);
        }
    }

    private Map<String, Object> decodeJson(String b64, String errorCode) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(b64);
            Map<String, Object> m = mapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
            return m == null ? new HashMap<>() : m;
        } catch (Exception e) {
            throw new OidcAuthException(errorCode, "Token segment is not valid JSON", e);
        }
    }
}
