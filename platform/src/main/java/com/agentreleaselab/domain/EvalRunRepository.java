package com.agentreleaselab.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {

    Optional<EvalRun> findByIdAndTenantId(UUID id, UUID tenantId);
    List<EvalRun> findByTenantIdAndAgentVersionIdAndDatasetIdAndModeOrderByScenarioIdAscTrialIndexAsc(
            UUID tenantId, UUID agentVersionId, String datasetId, String mode);
    List<EvalRun> findByTenantIdAndAgentVersionIdOrderByStartedAtDesc(UUID tenantId, UUID agentVersionId);
}
