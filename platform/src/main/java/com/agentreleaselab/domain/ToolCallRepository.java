package com.agentreleaselab.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {

    Optional<ToolCall> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
    List<ToolCall> findByEvalRunIdOrderByStartedAtAsc(UUID evalRunId);
    List<ToolCall> findByApprovalId(UUID approvalId);
    long countByEvalRunIdAndStatus(UUID evalRunId, String status);
}
