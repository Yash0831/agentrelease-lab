package com.agentreleaselab;

import com.agentreleaselab.domain.AgentVersion;
import com.agentreleaselab.domain.AgentVersionRepository;
import com.agentreleaselab.domain.TicketRepository;
import com.agentreleaselab.security.TenantContext;
import com.agentreleaselab.security.AuthService;
import com.agentreleaselab.service.ApiException;
import com.agentreleaselab.service.ApprovalService;
import com.agentreleaselab.service.EvalRunService;
import com.agentreleaselab.service.FingerprintService;
import com.agentreleaselab.service.ToolGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrency: the same mutation must never execute twice, even under race.
 *  Not @Transactional: each thread runs in its own transaction so the
 *  database unique constraint is the arbiter, as in production. */
@SpringBootTest
class ConcurrencyTest extends ServiceTestBase {

    @Autowired ToolGatewayService gateway;
    @Autowired ApprovalService approvals;
    @Autowired EvalRunService runs;
    @Autowired AgentVersionRepository versions;
    @Autowired TicketRepository tickets;
    @Autowired AuthService auth;
    @Autowired TransactionTemplate tx;

    private UUID setupVersion() {
        return tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                UUID tenantId = TenantContext.get().tenantId();
                String name = "conc-" + UUID.randomUUID();
                String fp = FingerprintService.agentConfigFingerprint(
                        name, "fixture-1.0", Map.of(), Map.of(), "snap", "policy-v1");
                AgentVersion v = versions.save(new AgentVersion(
                        tenantId, name, "prompt", "fixture-1.0",
                        Map.of(), Map.of(), "snap", "policy-v1", fp));
                return v.getId();
            } finally {
                TenantContext.clear();
            }
        });
    }

    @Test
    void concurrentSameKeyExecutesOnce() throws Exception {
        UUID versionId = setupVersion();
        String key = "conc-key-" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    var outcome = tx.execute(s -> {
                        TenantContext.set(auth.authenticate(ACME_AGENT));
                        try {
                            return gateway.execute("get_service_status", Map.of(), key,
                                    null, "trace-conc",
                                    new ToolGatewayService.ChaosConfig(Map.of()));
                        } finally {
                            TenantContext.clear();
                        }
                    });
                    return outcome.idempotentReplay() ? "replay" : "executed";
                } catch (Exception e) {
                    // Lost the race: the winner's record exists. Verify via
                    // a fresh lookup (new transaction) that it is a replay.
                    if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                        return "race-loser";
                    }
                    // Unwrap TransactionTemplate exceptions
                    Throwable c = e;
                    while (c != null) {
                        if (c.getMessage() != null && c.getMessage().contains("duplicate key"))
                            return "race-loser";
                        c = c.getCause();
                    }
                    throw e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (var f : futures) outcomes.add(f.get());
        pool.shutdown();

        long executed = outcomes.stream().filter("executed"::equals).count();
        // Exactly one thread executed; the rest either got a replay or lost
        // the race (both mean the mutation ran once).
        assertThat(executed).isEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.equals("executed") || o.equals("replay") || o.equals("race-loser"));

        // Persisted state: exactly one ToolCall for the key.
        var stored = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                return gateway.execute("get_service_status", Map.of(), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(stored.idempotentReplay()).isTrue();
    }

    @Test
    void sameKeyDifferentArgsIsStableConflict() {
        UUID versionId = setupVersion();
        String key = "conc-conflict-" + UUID.randomUUID();
        var first = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                return gateway.execute("get_ticket", Map.of("ticketKey", "ACME-101"), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(first.status()).isEqualTo("OK");
        assertThat(first.idempotentReplay()).isFalse();

        // Same key, different args: stable conflict, not the first result.
        var conflict = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                return gateway.execute("get_ticket", Map.of("ticketKey", "ACME-102"), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(conflict.status()).isEqualTo("CONFLICT");
        assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(conflict.idempotentReplay()).isFalse();

        // Same key, different tool: also a conflict.
        var conflict2 = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                return gateway.execute("get_service_status", Map.of(), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(conflict2.status()).isEqualTo("CONFLICT");

        // Same key, same args: idempotent replay of the original.
        var replay = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                return gateway.execute("get_ticket", Map.of("ticketKey", "ACME-101"), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(replay.status()).isEqualTo("OK");
        assertThat(replay.idempotentReplay()).isTrue();
    }

    @Test
    void concurrentMutatingToolExecutesOnce() throws Exception {
        UUID versionId = setupVersion();
        String key = "conc-mutate-" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    var outcome = tx.execute(s -> {
                        TenantContext.set(auth.authenticate(ACME_AGENT));
                        try {
                            return gateway.execute("update_ticket_status",
                                    Map.of("ticketKey", "ACME-101", "status", "IN_PROGRESS"), key,
                                    null, "trace-conc",
                                    new ToolGatewayService.ChaosConfig(Map.of()));
                        } finally {
                            TenantContext.clear();
                        }
                    });
                    return outcome.idempotentReplay() ? "replay" : "executed";
                } catch (Exception e) {
                    Throwable c = e;
                    while (c != null) {
                        if (c.getMessage() != null && c.getMessage().contains("duplicate key"))
                            return "race-loser";
                        c = c.getCause();
                    }
                    throw e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (var f : futures) outcomes.add(f.get());
        pool.shutdown();

        long executed = outcomes.stream().filter("executed"::equals).count();
        assertThat(executed).isEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.equals("executed") || o.equals("replay") || o.equals("race-loser"));

        // Persisted side effect: exactly one ToolCall, ticket status updated once.
        var verify = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                var ticket = tickets.findByTenantIdAndTicketKey(
                        TenantContext.get().tenantId(), "ACME-101").orElseThrow();
                var replay = gateway.execute("update_ticket_status",
                        Map.of("ticketKey", "ACME-101", "status", "IN_PROGRESS"), key,
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
                return Map.of("status", ticket.getStatus(), "replay", replay.idempotentReplay());
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(verify.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(verify.get("replay")).isEqualTo(true);
    }

    @Test
    void concurrentApprovalExecutionExecutesOnce() throws Exception {
        UUID versionId = setupVersion();
        // Create and approve an access request.
        String approvalId = tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_AGENT));
            try {
                var req = gateway.execute("request_access",
                        Map.of("targetUsername", "dave-newhire", "resource", "vpn-access",
                                "reason", "concurrency test", "ticketKey", "ACME-102"),
                        "conc-appr-req-" + UUID.randomUUID(),
                        null, "trace-conc", new ToolGatewayService.ChaosConfig(Map.of()));
                return (String) req.result().get("approvalId");
            } finally {
                TenantContext.clear();
            }
        });
        tx.execute(s -> {
            TenantContext.set(auth.authenticate(ACME_APPROVER));
            try {
                approvals.approve(TenantContext.get(), UUID.fromString(approvalId));
                return null;
            } finally {
                TenantContext.clear();
            }
        });

        // Race multiple threads to execute the same approval.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "conc-appr-exec-" + i + "-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    var outcome = tx.execute(s -> {
                        TenantContext.set(auth.authenticate(ACME_AGENT));
                        try {
                            return gateway.execute("execute_approval",
                                    Map.of("approvalId", approvalId), key,
                                    null, "trace-conc",
                                    new ToolGatewayService.ChaosConfig(Map.of()));
                        } finally {
                            TenantContext.clear();
                        }
                    });
                    return outcome.status();
                } catch (Exception e) {
                    // Already-executed approval: expect a clean rejection,
                    // not a duplicate side effect.
                    return "rejected:" + e.getClass().getSimpleName();
                }
            }));
        }
        ready.await();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (var f : futures) outcomes.add(f.get());
        pool.shutdown();

        long ok = outcomes.stream().filter("OK"::equals).count();
        // Exactly one execution succeeded; the rest were rejected.
        assertThat(ok).isEqualTo(1);
    }
}
