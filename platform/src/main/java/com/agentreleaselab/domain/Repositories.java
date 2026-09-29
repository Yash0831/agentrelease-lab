package com.agentreleaselab.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All Spring Data repositories. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public final class Repositories {
    private Repositories() {}

    public interface TenantRepository extends JpaRepository<Tenant, UUID> {
        Optional<Tenant> findBySlug(String slug);
    }

    public interface UserRepository extends JpaRepository<AppUser, UUID> {
        Optional<AppUser> findByApiKeyHash(String hash);
        Optional<AppUser> findByTenantIdAndUsername(UUID tenantId, String username);
        List<AppUser> findByTenantId(UUID tenantId);
    }

    public interface RunbookRepository extends JpaRepository<Runbook, UUID> {
        List<Runbook> findByTenantIdAndStatus(UUID tenantId, String status);
        Optional<Runbook> findByTenantIdAndSlugAndVersion(UUID tenantId, String slug, int version);
        List<Runbook> findByTenantIdAndSlugOrderByVersionDesc(UUID tenantId, String slug);

        /** Permission-aware vector search: tenant + CURRENT + role filter applied
         *  BEFORE distance ordering (ADR-0003). */
        @Query(value = """
            SELECT id FROM runbooks
            WHERE tenant_id = :tenantId AND status = 'CURRENT' AND :role = ANY(allowed_roles)
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
        List<UUID> searchIds(@Param("tenantId") UUID tenantId,
                             @Param("role") String role,
                             @Param("embedding") String embedding,
                             @Param("limit") int limit);
    }

    public interface TicketRepository extends JpaRepository<Ticket, UUID> {
        List<Ticket> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
        Optional<Ticket> findByTenantIdAndTicketKey(UUID tenantId, String ticketKey);
        long countByTenantId(UUID tenantId);
    }

    public interface ServiceStatusRepository extends JpaRepository<ServiceStatus, UUID> {
        List<ServiceStatus> findByTenantId(UUID tenantId);
        Optional<ServiceStatus> findByTenantIdAndServiceName(UUID tenantId, String serviceName);
    }

    public interface AccessGrantRepository extends JpaRepository<AccessGrant, UUID> {
        boolean existsByTenantIdAndTargetUsernameAndResource(UUID tenantId, String targetUsername, String resource);
        List<AccessGrant> findByTenantId(UUID tenantId);
    }

    public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
        List<Approval> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
        List<Approval> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
        Optional<Approval> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {
        Optional<AgentVersion> findByName(String name);
        Optional<AgentVersion> findByFingerprint(String fingerprint);
    }

    public interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {
        List<EvalRun> findByAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(
                UUID agentVersionId, String datasetId, String mode);
        List<EvalRun> findByDatasetIdAndMode(String datasetId, String mode);
        List<EvalRun> findByAgentVersionIdOrderByStartedAtDesc(UUID agentVersionId);
    }

    public interface TraceEventRepository extends JpaRepository<TraceEvent, Long> {
        List<TraceEvent> findByEvalRunIdOrderByTsAscIdAsc(UUID evalRunId);
        List<TraceEvent> findByTraceIdOrderByTsAsc(String traceId);
    }

    public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {
        Optional<ToolCall> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
        List<ToolCall> findByEvalRunIdOrderByStartedAtAsc(UUID evalRunId);
        long countByEvalRunIdAndStatus(UUID evalRunId, String status);
    }

    public interface ReleasePolicyRepository extends JpaRepository<ReleasePolicy, UUID> {
        Optional<ReleasePolicy> findByName(String name);
        List<ReleasePolicy> findByActiveTrue();
    }

    public interface ReleaseDecisionRepository extends JpaRepository<ReleaseDecision, UUID> {
        List<ReleaseDecision> findAllByOrderByDecidedAtDesc();
    }
}
