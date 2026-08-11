package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code PATCH /admin/api-keys/{clientId}} (US-04-008).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UpdateApiKeyEndpointIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    private static final ClientId CLIENT_ID = new ClientId("svc-ci");
    private static final String BCRYPT_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;

    @BeforeEach
    void resetSchemaAndClearAdminFlag() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void disable_then_re_enable_toggles_the_db_flag_round_trip() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedApiKey(false);

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", CLIENT_ID.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(CLIENT_ID.value()))
                .andExpect(jsonPath("$.disabled").value(true));
        assertThat(jdbcDisabled()).isTrue();

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", CLIENT_ID.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(false));
        assertThat(jdbcDisabled()).isFalse();
    }

    @Test
    void unknown_client_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", "does-not-exist")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missing_disabled_field_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedApiKey(false);

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", CLIENT_ID.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("disabled")));
    }

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        seedApiKey(false);
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", CLIENT_ID.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // The flag in DB has NOT moved.
        assertThat(jdbcDisabled()).isFalse();
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        seedApiKey(false);

        mockMvc.perform(patch("/api/v1/admin/api-keys/{cid}", CLIENT_ID.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private void seedApiKey(boolean disabled) {
        apiKeyRepository.save(new ApiKey(
                CLIENT_ID, BCRYPT_HASH, "ci", disabled, OffsetDateTime.now(ZoneOffset.UTC)));
    }

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

    private boolean jdbcDisabled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Boolean disabled = jdbc.queryForObject(
                "SELECT disabled FROM api_keys WHERE client_id = ?",
                Boolean.class, CLIENT_ID.value());
        return Boolean.TRUE.equals(disabled);
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
