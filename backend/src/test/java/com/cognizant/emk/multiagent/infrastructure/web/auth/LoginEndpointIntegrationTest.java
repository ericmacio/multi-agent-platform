package com.cognizant.emk.multiagent.infrastructure.web.auth;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code POST /auth/login} (US-03-009).
 *
 * <p>Overrides the {@code app_bootstrap_admin_password_hash} Flyway placeholder via
 * {@link DynamicPropertySource} so the seeded admin in {@code V002__seed_admin.sql} carries
 * a hash that matches the known cleartext {@link #SEEDED_ADMIN_PASSWORD}. Each test re-runs
 * Flyway clean+migrate so it starts from the documented seeded state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LoginEndpointIntegrationTest {

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
    @Autowired private UserRepository userRepository;
    @Autowired private Flyway flyway;

    @BeforeEach
    void resetSchema() {
        flyway.clean();
        flyway.migrate();
    }

    // ------- happy path -------

    @Test
    void valid_credentials_return_200_with_token_and_mustChangePassword_true() throws Exception {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();

        // Lifetime sanity: PT30M default, so expiresAt should be roughly 30 min ahead.
        String body = result.getResponse().getContentAsString();
        OffsetDateTime expiresAt = OffsetDateTime.parse(extract(body, "expiresAt"));
        assertThat(expiresAt).isBetween(before.plusMinutes(29), before.plusMinutes(31));
    }

    // ------- 401 cases (must be byte-identical bodies) -------

    @Test
    void wrong_password_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, "WrongPass!1A")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknown_email_returns_401_with_body_byte_identical_to_wrong_password() throws Exception {
        MvcResult wrongPasswordResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, "WrongPass!1A")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmailResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("nobody@example.test", "WrongPass!1A")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(unknownEmailResult.getResponse().getContentAsString())
                .isEqualTo(wrongPasswordResult.getResponse().getContentAsString());
    }

    @Test
    void disabled_user_returns_401_INVALID_CREDENTIALS() throws Exception {
        User admin = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow();
        User disabled = new User(
                admin.id(), admin.email(), admin.passwordHash(), admin.role(),
                true, admin.mustChangePassword(), admin.createdAt(), admin.updatedAt());
        userRepository.save(disabled);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- 400 validation cases -------

    @Test
    void empty_email_returns_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("", SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("email")));
    }

    @Test
    void empty_password_returns_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("password")));
    }

    @Test
    void password_below_policy_minimum_returns_400_with_password_field() throws Exception {
        // "@NotBlank" passes; the Password value-object constructor rejects with field "password".
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    // ------- end-to-end smoke -------

    @Test
    void issued_token_can_authenticate_protected_endpoints_after_clearing_the_flag() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = extract(body, "token");

        // Clear mustChangePassword at the repo level (US-03-011 will do this on a real
        // password change). The freshly issued JWT must then reach the probe handler.
        User admin = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(admin.passwordHash(),
                OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SEEDED_ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /**
     * Builds a JSON body without depending on a Spring-managed {@code ObjectMapper}. The
     * inputs are test-controlled so plain string concatenation is safe; no escaping is
     * needed for any of the values used here.
     */
    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    /** Minimal extractor for top-level string fields. Avoids pulling in Jackson at test time. */
    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("field '" + field + "' not found in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
