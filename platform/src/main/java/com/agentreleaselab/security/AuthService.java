package com.agentreleaselab.security;

import com.agentreleaselab.domain.UserRepository;
import com.agentreleaselab.domain.TenantRepository;
import com.agentreleaselab.domain.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** API-key authentication. Keys are stored as SHA-256 hashes; the raw key
 *  only exists in the seeder, tests, and the operator's .env. */
@Service
public class AuthService {

    private final UserRepository users;
    private final TenantRepository tenants;

    public AuthService(UserRepository users, TenantRepository tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    public TenantContext authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing X-API-Key");
        }
        AppUser user = users.findByApiKeyHash(sha256(apiKey))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
        String slug = tenants.findById(user.getTenantId()).map(t -> t.getSlug()).orElse("?");
        return new TenantContext(user.getTenantId(), user.getId(), user.getUsername(), user.getRole(), slug);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
