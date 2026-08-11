package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code POST /admin/api-keys} (US-04-006).
 *
 * <p>Drives an admin sign-in (override the bootstrap hash + clear the forced-change flag),
 * then exercises the happy path, label normalization, validation, authorization, and the
 * "cleartext-once + bcrypt-at-rest" invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CreateApiKeyEndpointIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private Flyway flyway;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void resetSchemaAndClearAdminFlag() throws Exception {
        flyway.clean();
        flyway.migrate();
        // Clear must_change_password on the seeded admin so the issued JWT can reach
        // protected endpoints; otherwise ForcedPasswordChangeFilter intercepts.
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void admin_creates_an_api_key_and_receives_cleartext_once() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        String body = mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ci\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.clientId").isString())
                .andExpect(jsonPath("$.apiKey").isString())
                .andExpect(jsonPath("$.label").value("ci"))
                .andExpect(jsonPath("$.disabled").value(false))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn().getResponse().getContentAsString();

        // Persisted row: BCrypt hash is shaped correctly and is not the cleartext.
        String clientId = extract(body, "clientId");
        String cleartext = extract(body, "apiKey");
        assertThat(clientId).matches("^[a-f0-9]{32}$");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String hashInDb = jdbc.queryForObject(
                "SELECT api_key_hash FROM api_keys WHERE client_id = ?",
                String.class, clientId);
        assertThat(hashInDb).matches("^\\$2[aby]\\$10\\$.{53}$");
        assertThat(hashInDb).isNotEqualTo(cleartext);
        // And it actually matches the cleartext through BCrypt.
        assertThat(BCRYPT.matches(cleartext, hashInDb)).isTrue();
    }

    @Test
    void missing_label_creates_with_null_label() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                // NON_NULL serialization: a null label is omitted from the response body.
                .andExpect(jsonPath("$.label").doesNotExist());
    }

    @Test
    void label_longer_than_128_chars_returns_400_VALIDATION_ERROR_field_label() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String tooLong = "a".repeat(129);

        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("label")));
    }

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ci\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ci\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private void seedStandardUser() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.save(new User(
                new UserId(UUID.randomUUID()),
                new Email(STANDARD_EMAIL),
                BCRYPT.encode(STANDARD_PASSWORD),
                Role.STANDARD,
                false,
                false,
                now,
                now));
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
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
