package com.agentreleaselab.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the dashboard SPA.
 *
 *  The dashboard is served from a different origin than the API in both the
 *  docker-compose deployment (dashboard :5173 -> platform :8080) and local
 *  `vite dev`. Without this, browsers block the dashboard's API calls at the
 *  preflight. Allowed origins are explicit and configurable via
 *  ARL_CORS_ALLOWED_ORIGINS (comma-separated); credentials are not used
 *  (auth is via the X-API-Key header), so a wildcard default is acceptable
 *  for this lab. Tighten for any shared deployment. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public CorsConfig(@Value("${ARL_CORS_ALLOWED_ORIGINS:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
