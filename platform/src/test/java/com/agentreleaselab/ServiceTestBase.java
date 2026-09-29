package com.agentreleaselab;

import com.agentreleaselab.security.AuthService;
import com.agentreleaselab.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Base for service-level tests. Uses the seeded synthetic demo data. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class ServiceTestBase {

    protected static final String ACME_AGENT = "arl-acme-agent-demo";
    protected static final String ACME_APPROVER = "arl-acme-approver-demo";
    protected static final String ACME_ADMIN = "arl-acme-admin-demo";
    protected static final String ACME_REQUESTER = "arl-acme-requester-demo";
    protected static final String GLOBEX_AGENT = "arl-globex-agent-demo";
    protected static final String GLOBEX_ADMIN = "arl-globex-admin-demo";

    @Autowired
    protected AuthService authService;

    protected TenantContext asUser(String apiKey) {
        TenantContext ctx = authService.authenticate(apiKey);
        TenantContext.set(ctx);
        return ctx;
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }
}
