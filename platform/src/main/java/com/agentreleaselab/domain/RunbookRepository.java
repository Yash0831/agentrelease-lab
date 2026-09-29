package com.agentreleaselab.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
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

    /** Variant that also surfaces STALE runbooks (failure-injection lab:
     *  the agent must reconcile stale evidence against live state). */
    @Query(value = """
        SELECT id FROM runbooks
        WHERE tenant_id = :tenantId AND status = ANY(:statuses) AND :role = ANY(allowed_roles)
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> searchIdsWithStatuses(@Param("tenantId") UUID tenantId,
                                    @Param("role") String role,
                                    @Param("embedding") String embedding,
                                    @Param("limit") int limit,
                                    @Param("statuses") String[] statuses);
}
