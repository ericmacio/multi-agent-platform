package com.cognizant.emk.multiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the default {@code /api/v1} prefix wiring: the dev {@code PingController} is
 * reachable at {@code /api/v1/ping} and not at the un-prefixed {@code /ping}.
 *
 * <p>Security filters are disabled in this slice so the test focuses on the prefix only;
 * authentication-related assertions are owned by EPIC-03 tests.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("dev")
class BasePathConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prefixed_path_is_reachable() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void unprefixed_path_is_not_reachable() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isNotFound());
    }
}
