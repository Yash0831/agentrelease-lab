package com.agentreleaselab;

import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.service.ToolGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Tool gateway: allowlist, schema validation, idempotency, budgets, timeouts. */
@Transactional
class GatewayTest extends ServiceTestBase {

    @Autowired ToolGatewayService gateway;
    @Autowired Repositories.ToolCallRepository toolCalls;
    @Autowired Repositories.TicketRepository tickets;

    private ToolGatewayService.ToolOutcome call(String tool, Map<String, Object> args, String key) {
        return gateway.execute(tool, args, key, null, "trace-test",
                new ToolGatewayService.ChaosConfig(Map.of()));
    }

    @Test
    void unknownToolIsRejected() {
        asUser(ACME_AGENT);
        var o = call("grant_access", Map.of("targetUsername", "x", "resource", "y"), "k-unknown-1");
        assertThat(o.status()).isEqualTo("TOOL_UNKNOWN");
        assertThat(o.errorCode()).isEqualTo("TOOL_UNKNOWN");
    }

    @Test
    void malformedArgsAreRejectedWithDetails() {
        asUser(ACME_AGENT);
        var o = call("update_ticket_status",
                Map.of("ticketKey", "ACME-101", "status", "VAPORIZED"), "k-malformed-1");
        assertThat(o.status()).isEqualTo("ARG_INVALID");
        assertThat(String.valueOf(o.result().get("error_message"))).contains("status must be one of");

        var o2 = call("search_runbooks", Map.of(), "k-malformed-2");
        assertThat(o2.status()).isEqualTo("ARG_INVALID");
    }

    @Test
    void idempotencyKeyPreventsDuplicateSideEffects() {
        asUser(ACME_AGENT);
        String key = "k-idem-" + UUID.randomUUID();
        var first = call("update_ticket_status",
                Map.of("ticketKey", "ACME-101", "status", "IN_PROGRESS"), key);
        assertThat(first.status()).isEqualTo("OK");
        assertThat(first.idempotentReplay()).isFalse();

        // Simulate a client retry after a lost response: same key, same args.
        var retry = call("update_ticket_status",
                Map.of("ticketKey", "ACME-101", "status", "IN_PROGRESS"), key);
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.status()).isEqualTo("OK");

        // Exactly one persisted tool call for that key, and the ticket moved once.
        assertThat(toolCalls.findByTenantIdAndIdempotencyKey(TenantContext.get().tenantId(), key)).isPresent();
        var ticket = tickets.findByTenantIdAndTicketKey(TenantContext.get().tenantId(), "ACME-101").orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void requestAccessCreatesApprovalWithoutSideEffect() {
        asUser(ACME_AGENT);
        var o = call("request_access", Map.of(
                "targetUsername", "carol-requester", "resource", "vpn-access",
                "reason", "onboarding", "ticketKey", "ACME-102"), "k-req-1");
        assertThat(o.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(o.approvalId()).isNotNull();
        // No grant exists yet — the agent cannot self-authorize.
        assertThat(o.result()).containsEntry("status", "PENDING");
    }

    @Test
    void timeoutIsEnforcedViaChaosDelay() {
        asUser(ACME_AGENT);
        // Build chaos directly: 3s delay with 500ms gateway timeout.
        var chaos = new ToolGatewayService.ChaosConfig(Map.of(
                "tool_delays_ms", Map.of("get_ticket", 3000),
                "gateway_timeout_ms", 500));
        var o = gateway.execute("get_ticket", Map.of("ticketKey", "ACME-101"),
                "k-timeout-1", null, "trace-test", chaos);
        assertThat(o.status()).isEqualTo("TIMEOUT");
        assertThat(o.errorCode()).isEqualTo("TOOL_TIMEOUT");
    }

    @Test
    void actionBudgetIsEnforced() {
        asUser(ACME_AGENT);
        var chaos = new ToolGatewayService.ChaosConfig(Map.of("action_budget", 2));
        // Budget applies per eval run; create a throwaway run id to attach the budget to.
        UUID runId = UUID.randomUUID();
        var c1 = gateway.execute("get_service_status", Map.of(), "k-b1", runId, "t", chaos);
        var c2 = gateway.execute("get_service_status", Map.of(), "k-b2", runId, "t", chaos);
        assertThat(c1.status()).isEqualTo("OK");
        assertThat(c2.status()).isEqualTo("OK");
        var c3 = gateway.execute("get_service_status", Map.of(), "k-b3", runId, "t", chaos);
        assertThat(c3.status()).isEqualTo("BUDGET_EXCEEDED");
    }
}
