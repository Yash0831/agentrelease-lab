package com.agentreleaselab.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Resolves X-API-Key -> TenantContext for /api/**. Public: /api/health. */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public ApiKeyAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.equals("/api/health") || p.startsWith("/actuator") || !p.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            TenantContext ctx = authService.authenticate(request.getHeader("X-API-Key"));
            TenantContext.set(ctx);
            try {
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"code\":\"AUTH_REQUIRED\"}");
        }
    }
}
