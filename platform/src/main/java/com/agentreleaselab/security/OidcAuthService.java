package com.agentreleaselab.security;

import com.agentreleaselab.domain.AppUser;
import com.agentreleaselab.domain.Tenant;
import com.agentreleaselab.domain.TenantRepository;
import com.agentreleaselab.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** OIDC authentication. Validates the JWT, then maps its claims onto the
 *  existing TenantContext: the tenant claim (configurable, default
 *  tenant_slug) resolves to a tenant row, the role claim must be one of the
 *  existing roles, and the scope claim populates the scope set used by the
 *  scope-or-role authorization gates. Unknown tenant or subject -> 401.
 *
 *  Service identities (e.g. the agent worker) stay on API keys: a dedicated
 *  api-key user with the AGENT role in its tenant, provisioned via
 *  PLATFORM_API_KEY. */
@Service
public class OidcAuthService {

    public static final String SCOPE_EVALUATE = "arl:evaluate";
    public static final String SCOPE_APPROVE = "arl:approve";
    public static final String SCOPE_ADMIN = "arl:admin";
    public static final String SCOPE_READ = "arl:read";

    private static final Set<String> KNOWN_ROLES =
            Set.of("ADMIN", "APPROVER", "AGENT", "REQUESTER", "VIEWER");

    private final OidcTokenValidator validator;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final OidcProperties props;

    public OidcAuthService(OidcTokenValidator validator, TenantRepository tenants,
                           UserRepository users, OidcProperties props) {
        this.validator = validator;
        this.tenants = tenants;
        this.users = users;
        this.props = props;
    }

    @Transactional
    public TenantContext authenticate(String token) {
        Map<String, Object> claims;
        try {
            claims = validator.validate(token);
        } catch (OidcAuthException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getCode() + ": " + e.getMessage(), e);
        }
        String sub = str(claims.get("sub"));
        if (sub == null || sub.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC_BAD_CLAIMS: missing sub");
        }
        String tenantSlug = firstPresent(
                str(claims.get(props.tenantClaim())),
                str(claims.get("tenant_slug")),
                str(claims.get("tenant_id")));
        Tenant tenant = tenantSlug == null ? null : tenants.findBySlug(tenantSlug).orElse(null);
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC_UNKNOWN_TENANT: unknown tenant");
        }
        String role = str(claims.get(props.roleClaim()));
        role = role == null ? null : role.toUpperCase();
        if (!KNOWN_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC_BAD_CLAIMS: unknown role");
        }
        Set<String> scopes = parseScopes(firstPresent(
                claims.get(props.scopeClaim()), claims.get("scp")));
        // Just-in-time provisioning: audit fields (approvals.approver_id,
        // approvals.requester_id, access_grants.granted_by) are foreign keys
        // to app_users, so an OIDC subject needs a real row before it can
        // approve or execute. The row's api_key_hash is a hashed sentinel
        // that is never issued as a credential, so the provisioned user can
        // only ever sign in via OIDC. The token's role stays authoritative
        // for this request; the stored role is the provisioning-time role.
        final String tokenRole = role;
        AppUser user = users.findByTenantIdAndUsername(tenant.getId(), sub)
                .orElseGet(() -> provision(tenant.getId(), sub, tokenRole));
        return new TenantContext(tenant.getId(), user.getId(), sub, role, tenant.getSlug(), scopes, "oidc");
    }

    private AppUser provision(UUID tenantId, String sub, String role) {
        String sentinelHash = AuthService.sha256(
                "oidc-provisioned:" + props.issuer() + ":" + tenantId + ":" + sub);
        try {
            return users.saveAndFlush(new AppUser(tenantId, sub, sub, sentinelHash, role, null));
        } catch (DataIntegrityViolationException e) {
            // Concurrent first sign-in for the same subject: use the winner's row.
            return users.findByTenantIdAndUsername(tenantId, sub)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "OIDC_PROVISIONING_FAILED"));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstPresent(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Object firstPresent(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }

    /** Accepts a space-separated string ("arl:read arl:evaluate") or a JSON
     *  array of strings, per the 'scope' / 'scp' claim conventions. */
    static Set<String> parseScopes(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof String s) {
            for (String part : s.split("\\s+")) {
                if (!part.isBlank()) out.add(part);
            }
        } else if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
