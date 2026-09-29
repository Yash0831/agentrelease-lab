package com.agentreleaselab.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {

    Optional<AgentVersion> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<AgentVersion> findByTenantIdAndName(UUID tenantId, String name);
    Optional<AgentVersion> findByTenantIdAndFingerprint(UUID tenantId, String fingerprint);
    List<AgentVersion> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
