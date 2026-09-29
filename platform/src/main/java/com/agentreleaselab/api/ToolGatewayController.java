package com.agentreleaselab.api;

import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ToolGatewayService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Tool execution gateway endpoint. The agent worker calls tools ONLY here. */
@RestController
@RequestMapping("/api/tools")
public class ToolGatewayController {

    private final ToolGatewayService gateway;

    public ToolGatewayController(ToolGatewayService gateway) {
        this.gateway = gateway;
    }

    public record ExecuteRequest(@NotBlank String tool, Map<String, Object> args,
                                 @NotBlank String idempotencyKey, UUID evalRunId, String traceId) {}

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteRequest body) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.hasAnyRole("AGENT", "ADMIN")) {
            throw new com.agentreleaselab.service.ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "TOOL_CALL_DENIED",
                    "Role '" + ctx.role() + "' may not call tools through the gateway");
        }
        ToolGatewayService.ToolOutcome o = gateway.execute(body.tool(), body.args(),
                body.idempotencyKey(), body.evalRunId(), body.traceId());
        return Map.of(
                "status", o.status(),
                "result", o.result(),
                "errorCode", o.errorCode() == null ? "" : o.errorCode(),
                "errorMessage", o.errorMessage() == null ? "" : o.errorMessage(),
                "approvalId", o.approvalId() == null ? "" : o.approvalId().toString(),
                "idempotentReplay", o.idempotentReplay());
    }

    @GetMapping("/allowlist")
    public Map<String, Object> allowlist() {
        return Map.of("tools", ToolGatewayService.AGENT_TOOLS.stream().sorted().toList());
    }
}
