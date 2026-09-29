package com.agentreleaselab.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    List<Approval> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
    List<Approval> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<Approval> findByIdAndTenantId(UUID id, UUID tenantId);
    /** Row lock for the approve/execute critical section (ADR-0005):
     *  exactly-once execution under concurrency. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id and a.tenantId = :tenantId")
    Optional<Approval> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
