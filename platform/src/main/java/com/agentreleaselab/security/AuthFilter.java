package com.agentreleaselab.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** Resolves identity for /api/**. Two paths, same TenantContext downstream:
 *  <ul>
 *    <li>{@code Authorization: Bearer <jwt>} — OIDC token, only when
 *        {@code arl.oidc.enabled} is true. Full JWT validation (signature,
 *        issuer, audience, expiry, tenant/role/scope claims).</li>
 *    <li>{@code X-API-Key} — local/dev and service identities (e.g. the
 *        worker's PLATFORM_API_KEY, a dedicated api-key user with the AGENT
 *        role in its tenant).</li>
 *  </ul>
 *  Public: /api/health and /api/health/ready. */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final OidcProperties oidcProps;
    private final OidcAuthService oidcAuth;

    public AuthFilter(AuthService authService, OidcProperties oidcProps, OidcAuthService oidcAuth) {
        this.authService = authService;
        this.oidcProps = oidcProps;
        this.oidcAuth = oidcAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        // CORS preflights carry no credentials; the CORS config answers them.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return p.equals("/api/health") || p.startsWith("/api/health/")
                || p.startsWith("/actuator") || !p.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            TenantContext ctx = resolve(request);
            TenantContext.set(ctx);
            try {
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        } catch (OidcAuthException e) {
            reject(response, e.getCode());
        } catch (ResponseStatusException e) {
            reject(response, e.getStatusCode().value() == 401 ? "AUTH_REQUIRED" : "AUTH_ERROR");
        } catch (Exception e) {
            reject(response, "AUTH_REQUIRED");
        }
    }

    private TenantContext resolve(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && oidcProps.isEnabled()) {
            return oidcAuth.authenticate(auth.substring(7).trim());
        }
        return authService.authenticate(request.getHeader("X-API-Key"));
    }

    private void reject(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\",\"code\":\"" + code + "\"}");
    }
}
