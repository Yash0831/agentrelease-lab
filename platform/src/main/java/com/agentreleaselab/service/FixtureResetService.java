package com.agentreleaselab.service;

import com.agentreleaselab.domain.AccessGrantRepository;
import com.agentreleaselab.domain.ApprovalRepository;
import com.agentreleaselab.domain.ServiceStatusRepository;
import com.agentreleaselab.domain.TicketRepository;
import com.agentreleaselab.domain.ToolCallRepository;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Resets eval-mutable fixtures to their seeded state between trials.

 *  Each trial must stand on its own evidence: without a reset, trial 0 could
 *  set ACME-101 to IN_PROGRESS (or create a dave-newhire approval) and trial
 *  1's checkers would pass without the agent doing anything. The worker calls
 *  this before every trial (see worker.py). Seed states mirror DataSeeder.
 */
@Service
public class FixtureResetService {

    // ticketKey -> seed status (DataSeeder)
    private static final Map<String, String> SEED_TICKET_STATUS = Map.of(
            "ACME-101", "OPEN",
            "ACME-102", "OPEN",
            "ACME-103", "RESOLVED",
            "GLBX-201", "OPEN");

    // serviceName -> seed status (DataSeeder)
    private static final Map<String, String> SEED_SERVICE_STATUS = Map.of(
            "vpn", "DEGRADED",
            "wifi", "OPERATIONAL",
            "email", "OPERATIONAL",
            "okta", "OPERATIONAL");

    private final TicketRepository tickets;
    private final ApprovalRepository approvals;
    private final AccessGrantRepository grants;
    private final ServiceStatusRepository serviceStatus;
    private final ToolCallRepository toolCalls;

    public FixtureResetService(TicketRepository tickets, ApprovalRepository approvals,
                               AccessGrantRepository grants, ServiceStatusRepository serviceStatus,
                               ToolCallRepository toolCalls) {
        this.tickets = tickets;
        this.approvals = approvals;
        this.grants = grants;
        this.serviceStatus = serviceStatus;
        this.toolCalls = toolCalls;
    }

    @Transactional
    public Map<String, Object> reset() {
        UUID tenantId = TenantContext.get().tenantId();
        int ticketsReset = 0;
        for (var e : SEED_TICKET_STATUS.entrySet()) {
            var t = tickets.findByTenantIdAndTicketKey(tenantId, e.getKey());
            if (t.isPresent() && !e.getValue().equals(t.get().getStatus())) {
                t.get().setStatus(e.getValue());
                tickets.save(t.get());
                ticketsReset++;
            }
        }
        // Approvals and grants are never seeded; anything present was created
        // by an earlier trial and must not leak into the next one. Break the
        // tool_calls -> approvals FK first (execution records are kept).
        int approvalsDeleted = 0;
        for (var a : approvals.findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            for (var tc : toolCalls.findByApprovalId(a.getId())) {
                tc.setApprovalId(null);
                toolCalls.save(tc);
            }
            approvals.delete(a);
            approvalsDeleted++;
        }
        int grantsDeleted = 0;
        for (var g : grants.findByTenantId(tenantId)) {
            grants.delete(g);
            grantsDeleted++;
        }
        int servicesReset = 0;
        for (var s : serviceStatus.findByTenantId(tenantId)) {
            String seed = SEED_SERVICE_STATUS.get(s.getServiceName());
            if (seed != null && !seed.equals(s.getStatus())) {
                s.setStatus(seed);
                serviceStatus.save(s);
                servicesReset++;
            }
        }
        return Map.of("ticketsReset", ticketsReset, "approvalsDeleted", approvalsDeleted,
                "grantsDeleted", grantsDeleted, "servicesReset", servicesReset);
    }
}
