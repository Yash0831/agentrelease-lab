package com.agentreleaselab.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public health endpoints (no auth). /api/health keeps its historical shape
 *  ({status, service}) and adds component checks; /api/health/ready is the
 *  readiness probe (200 when every component is up, 503 otherwise). */
@RestController
public class HealthController {

    private final DataSource dataSource;
    private final String mode;

    public HealthController(DataSource dataSource, @Value("${arl.mode:local}") String mode) {
        this.dataSource = dataSource;
        this.mode = mode == null ? "local" : mode;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Map<String, String>> components = components();
        boolean allUp = components.values().stream().allMatch(c -> "up".equals(c.get("status")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "ok" : "degraded");
        body.put("service", "agentrelease-lab-platform");
        body.put("mode", mode);
        body.put("components", components);
        return body;
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Map<String, String>> components = components();
        boolean allUp = components.values().stream().allMatch(c -> "up".equals(c.get("status")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "ready" : "not-ready");
        body.put("service", "agentrelease-lab-platform");
        body.put("components", components);
        return ResponseEntity.status(allUp ? 200 : 503).body(body);
    }

    private Map<String, Map<String, String>> components() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        out.put("database", databaseComponent());
        return out;
    }

    /** Database connectivity plus Flyway schema-history presence. Details are
     *  coarse on purpose — never include connection strings or credentials. */
    private Map<String, String> databaseComponent() {
        try (Connection c = dataSource.getConnection()) {
            if (!c.isValid(2)) {
                return Map.of("status", "down", "detail", "connection not valid");
            }
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
                rs.next();
                return Map.of("status", "up",
                        "migrations_applied", String.valueOf(rs.getLong(1)));
            } catch (Exception e) {
                return Map.of("status", "down", "detail", "schema history unreadable");
            }
        } catch (Exception e) {
            return Map.of("status", "down", "detail", "connection failed");
        }
    }
}
