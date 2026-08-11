package com.cognizant.emk.multiagent.infrastructure.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke checks on the EPIC-03 security chain: anonymous access to a protected
 * {@code /api/v1/**} path is rejected with the documented 401 envelope; the public login
 * endpoint is reachable; the configured CORS allow-list is enforced for pre-flight
 * requests. The full positive-path JWT round-trip is exercised by
 * {@link JwtAuthenticationFilterIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SpringSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protected_path_without_token_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_endpoint_is_publicly_reachable() throws Exception {
        // What we assert here is that the security chain does NOT reject the call with 401.
        // The handler shipped by US-03-009 then runs bean validation on the empty body and
        // surfaces 400 VALIDATION_ERROR through the GlobalExceptionHandler.
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cors_preflight_from_allowed_origin_is_accepted() throws Exception {
        mockMvc.perform(options("/api/v1/ping")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void cors_preflight_from_unknown_origin_is_rejected() throws Exception {
        mockMvc.perform(options("/api/v1/ping")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
