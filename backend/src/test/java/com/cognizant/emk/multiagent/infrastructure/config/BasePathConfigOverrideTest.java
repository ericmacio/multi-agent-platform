package com.cognizant.emk.multiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the prefix is configurable: overriding {@code app.api.base-path} to {@code /api/v2}
 * relocates every {@code @RestController} accordingly, and the previous prefix becomes invalid.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.api.base-path=/api/v2")
class BasePathConfigOverrideTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overridden_prefix_is_used() throws Exception {
        mockMvc.perform(get("/api/v2/ping")).andExpect(status().isOk());
    }

    @Test
    void default_prefix_is_no_longer_reachable() throws Exception {
        mockMvc.perform(get("/api/v1/ping")).andExpect(status().isNotFound());
    }
}
