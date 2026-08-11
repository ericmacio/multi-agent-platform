package com.cognizant.emk.multiagent.infrastructure.web.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@code POST /auth/login} treats the email field case-insensitively
 * (US-CR1-001) without regressing the byte-identity invariant of
 * {@code REQ-AUTH-009} (unknown email and wrong password must return the same body).
 *
 * <p>Re-uses the seeded admin from {@code V002__seed_admin.sql} with a known
 * cleartext via the {@code app_bootstrap_admin_password_hash} Flyway placeholder,
 * exactly like {@link LoginEndpointIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LoginEmailCaseInsensitiveIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";
    private static final String SEEDED_ADMIN_PASSWORD = "Bootstrap!1A";
    private static final String SEEDED_ADMIN_PASSWORD_HASH =
            new BCryptPasswordEncoder().encode(SEEDED_ADMIN_PASSWORD);

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> SEEDED_ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private Flyway flyway;

    @BeforeEach
    void resetSchema() {
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void login_succeeds_when_email_is_sent_in_mixed_case() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Bootstrap@Example.Test", SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void login_succeeds_when_email_is_sent_in_upper_case() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("BOOTSTRAP@EXAMPLE.TEST", SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_succeeds_when_email_is_sent_in_lower_case() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Guard against accidentally weakening REQ-AUTH-009: a wrong-password attempt with
     * a mixed-case email must still return a body byte-identical to an unknown-email
     * attempt. Otherwise a casing-aware caller could probe email existence.
     */
    @Test
    void mixed_case_wrong_password_body_is_byte_identical_to_unknown_email_body() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Bootstrap@Example.Test", "WrongPass!1A")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Nobody@Example.Test", "WrongPass!1A")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(unknownEmail.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
