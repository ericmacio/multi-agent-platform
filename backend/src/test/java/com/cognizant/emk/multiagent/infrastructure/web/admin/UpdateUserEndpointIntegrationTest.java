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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code PATCH /admin/users/{userId}} (US-05-007).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UpdateUserEndpointIntegrationTest {

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
        UserId seeded = seedStandardUser();

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", seeded.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seeded.value().toString()))
                .andExpect(jsonPath("$.disabled").value(true));
        assertThat(jdbcDisabled(seeded)).isTrue();

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", seeded.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(false));
        assertThat(jdbcDisabled(seeded)).isFalse();
    }

    @Test
    void unknown_user_returns_404_NOT_FOUND() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missing_disabled_field_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        UserId seeded = seedStandardUser();

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", seeded.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("disabled")));
    }

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        UserId seeded = seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", seeded.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcDisabled(seeded)).isFalse();
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        UserId seeded = seedStandardUser();

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", seeded.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void admin_self_disable_succeeds_and_subsequent_login_returns_401() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        UserId adminId = new UserId(
                userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow().id().value());

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", adminId.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(true));

        // LoginService rejects disabled accounts as INVALID_CREDENTIALS so a disabled
        // admin cannot regain access by simply re-authenticating.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private UserId seedStandardUser() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id,
                new Email(STANDARD_EMAIL),
                BCRYPT.encode(STANDARD_PASSWORD),
                Role.STANDARD,
                false,
                false,
                now,
                now));
        return id;
    }

    private boolean jdbcDisabled(UserId id) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Boolean disabled = jdbc.queryForObject(
                "SELECT disabled FROM users WHERE id = ?",
                Boolean.class, id.value());
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
