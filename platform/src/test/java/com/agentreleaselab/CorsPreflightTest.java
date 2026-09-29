package com.agentreleaselab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dashboard SPA calls the API cross-origin; preflights must succeed
 *  without an API key (the CORS config answers them, the auth filter skips
 *  OPTIONS). Authenticated requests are still enforced (see TenantIsolationTest). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsPreflightTest {

    @Autowired MockMvc mvc;

    @Test
    void optionsPreflightSucceedsWithoutApiKey() throws Exception {
        mvc.perform(options("/api/agent-versions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-API-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void getWithoutApiKeyIsStillRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/agent-versions")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }
}
