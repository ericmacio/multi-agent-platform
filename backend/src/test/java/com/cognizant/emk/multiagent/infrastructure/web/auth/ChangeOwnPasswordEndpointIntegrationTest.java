package com.cognizant.emk.multiagent.infrastructure.web.auth;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code PUT /auth/password} (US-03-011).
 *
 * <p>Drives the full forced-password-change flow on the seeded admin: sign in (with
 * {@code mustChangePassword=true}), change the password, then verify that the same JWT now
 * reaches non-allow-list endpoints, that the new password is the only one that authenticates,
 * and that the documented validation / authentication failures land correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChangeOwnPasswordEndpointIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";
    private static final String SEEDED_ADMIN_PASSWORD = "Bootstrap!1A";
    private static final String NEW_PASSWORD = "Brand!New2Z";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String SEEDED_ADMIN_PASSWORD_HASH = BCRYPT.encode(SEEDED_ADMIN_PASSWORD);

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

    @Test
    void successful_change_returns_204_persists_new_hash_and_clears_the_flag() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);
        String oldHash = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow().passwordHash();

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(SEEDED_ADMIN_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        User after = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow();
        assertThat(after.passwordHash()).isNotEqualTo(oldHash);
        assertThat(BCRYPT.matches(NEW_PASSWORD, after.passwordHash())).isTrue();
        assertThat(after.mustChangePassword()).isFalse();
    }

    @Test
    void same_jwt_can_reach_a_protected_endpoint_after_the_flag_is_cleared() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);

        // Before the change: ForcedPasswordChangeFilter blocks the probe with 403.
        mockMvc.perform(get("/api/v1/__test/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MUST_CHANGE_PASSWORD"));

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(SEEDED_ADMIN_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        // After the change: same JWT reaches the probe handler — REQ-AUTH-006 keeps the JWT
        // valid until natural expiry, REQ-USR-007 lifts the forced-change block.
        mockMvc.perform(get("/api/v1/__test/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SEEDED_ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void old_password_no_longer_authenticates_after_the_change_but_new_one_does() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(SEEDED_ADMIN_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SEEDED_ADMIN_EMAIL, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                // mustChangePassword has been cleared by the prior change.
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void wrong_current_password_returns_401_INVALID_CREDENTIALS() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("WrongCurr!1A", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // The change must NOT have been applied: the seeded hash is still there.
        User after = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow();
        assertThat(BCRYPT.matches(SEEDED_ADMIN_PASSWORD, after.passwordHash())).isTrue();
        assertThat(after.mustChangePassword()).isTrue();
    }

    @Test
    void new_password_below_policy_returns_400_with_field_newPassword() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(SEEDED_ADMIN_PASSWORD, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void empty_new_password_returns_400_via_NotBlank_binding_validation() throws Exception {
        String token = login(SEEDED_ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(SEEDED_ADMIN_PASSWORD, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("newPassword")));
    }

    private String login(String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SEEDED_ADMIN_EMAIL, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String passwordBody(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

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
