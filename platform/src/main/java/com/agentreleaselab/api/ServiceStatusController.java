package com.agentreleaselab.api;

import com.agentreleaselab.domain.ServiceStatusRepository;
import com.agentreleaselab.domain.ServiceStatus;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Service status board. */
@RestController
@RequestMapping("/api/service-status")
public class ServiceStatusController {

    private final ServiceStatusRepository repo;

    public ServiceStatusController(ServiceStatusRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findByTenantId(TenantContext.get().tenantId()).stream()
                .map(s -> Map.<String, Object>of("serviceName", s.getServiceName(), "status", s.getStatus(),
                        "message", s.getMessage() == null ? "" : s.getMessage(),
                        "updatedAt", s.getUpdatedAt().toString()))
                .toList();
    }

    public record StatusUpdate(String status, String message) {}

    @PutMapping("/{serviceName}")
    public Map<String, Object> update(@PathVariable String serviceName, @RequestBody StatusUpdate body) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.hasAnyRole("ADMIN")) {
            throw ApiException.forbidden("STATUS_UPDATE_DENIED", "Only admins update service status");
        }
        ServiceStatus s = repo.findByTenantIdAndServiceName(ctx.tenantId(), serviceName)
                .orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "No such service"));
        if (body.status() != null) s.setStatus(body.status());
        if (body.message() != null) s.setMessage(body.message());
        repo.save(s);
        return Map.of("serviceName", s.getServiceName(), "status", s.getStatus());
    }
}
