package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc test for {@code GET /admin/rate-limit} and
 * {@code PUT /admin/rate-limit} (US-13-006). Boots the full Spring context so
 * the URL guard, {@code @PreAuthorize}, Bean Validation, and the
 * {@code GlobalExceptionHandler} envelope are all exercised together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RateLimitAdminControllerIntegrationTest {

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
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private Flyway flyway;

    @BeforeEach
    void resetSchemaAndClearAdminFlag() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    // ------- GET /admin/rate-limit -------

    @Test
    void get_as_admin_returns_post_migration_values_with_updatedBy_null() throws Exception {
        // The test-only V900 override (src/test/resources/db/migration-test/) bumps
        // the production V003 seed (10, 50) to high values so the broader test suite
        // is not throttled by the EPIC-13 filter. The load-bearing assertion here is
        // that GET returns the live row with `updatedBy=null` (no admin has written
        // since the seed). The exact counter values are asserted against the bumped
        // seed; the production-seed-asserting test lives in
        // RateLimitConfigRepositoryAdapterIntegrationTest / SeedMigrationsTest where
        // spring.flyway.locations is restricted to production migrations only.
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perMinute").value(100000))
                .andExpect(jsonPath("$.perHour").value(1000000))
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());
    }

    @Test
    void get_as_standard_user_is_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void get_unauthenticated_is_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/admin/rate-limit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void get_as_SYSTEM_api_key_caller_is_403_FORBIDDEN() throws Exception {
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "system-caller",
                false,
                OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/admin/rate-limit")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------- PUT /admin/rate-limit -------

    @Test
    void put_as_admin_updates_counters_and_subsequent_get_sees_them() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        UUID adminId = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow()
                .id().value();

        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perMinute\":30,\"perHour\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perMinute").value(30))
                .andExpect(jsonPath("$.perHour").value(200))
                .andExpect(jsonPath("$.updatedBy").value(adminId.toString()));

        mockMvc.perform(get("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perMinute").value(30))
                .andExpect(jsonPath("$.perHour").value(200))
                .andExpect(jsonPath("$.updatedBy").value(adminId.toString()));
    }

    @Test
    void put_with_zero_perMinute_returns_400_VALIDATION_ERROR_with_field_name() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perMinute\":0,\"perHour\":50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("perMinute"));
    }

    @Test
    void put_with_empty_body_returns_400() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void put_as_standard_user_is_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perMinute\":30,\"perHour\":200}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
