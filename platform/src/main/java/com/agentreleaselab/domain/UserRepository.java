package com.agentreleaselab.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository. Every query that touches tenant data takes
 *  the tenant id as a parameter — tenant isolation is enforced at the query
 *  layer, not just the controller layer. */
public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByApiKeyHash(String hash);
    Optional<AppUser> findByTenantIdAndUsername(UUID tenantId, String username);
    List<AppUser> findByTenantId(UUID tenantId);
}
