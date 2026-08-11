package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /admin/api-keys} (US-04-007). Asserts the
 * paginated response shape, cursor round-trip, validation, and the "metadata-only,
 * no cleartext / no hash" invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListApiKeysEndpointIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetSchemaAndClearAdminFlag() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void paginates_through_three_rows_with_size_two() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedApiKeys();

        String first = mockMvc.perform(get("/api/v1/admin/api-keys?pageSize=2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].clientId").value("svc-c"))
                .andExpect(jsonPath("$.items[1].clientId").value("svc-b"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.pageSize").value(2))
                .andReturn().getResponse().getContentAsString();

        String cursor = extract(first, "nextCursor");
        mockMvc.perform(get("/api/v1/admin/api-keys?pageSize=2&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].clientId").value("svc-a"))
                // NON_NULL serialization: last page omits nextCursor entirely.
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.pageSize").value(2));
    }

    @Test
    void list_response_carries_only_metadata_no_cleartext_or_hash() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedApiKeys();

        String body = mockMvc.perform(get("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        Set<String> topLevel = collectFieldNames(root);
        assertThat(topLevel).containsExactlyInAnyOrder("items", "pageSize");

        JsonNode items = root.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isGreaterThan(0);
        for (JsonNode item : items) {
            Set<String> fields = collectFieldNames(item);
            assertThat(fields).containsExactlyInAnyOrder("clientId", "label", "disabled", "createdAt");
            // Defense in depth: explicitly assert the sensitive fields are absent.
            assertThat(item.get("apiKey")).isNull();
            assertThat(item.get("apiKeyHash")).isNull();
        }

        // And there is no api_key_hash leak anywhere in the raw response body.
        assertThat(body).doesNotContain("apiKey").doesNotContain("apiKeyHash");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Verify the persisted hash is what we seeded — guards against accidental DB
        // mutation by the list path.
        String hashInDb = jdbc.queryForObject(
                "SELECT api_key_hash FROM api_keys WHERE client_id = 'svc-a'", String.class);
        assertThat(hashInDb).isEqualTo(BCRYPT_HASH);
    }

    @Test
    void page_size_zero_returns_400_VALIDATION_ERROR_field_page_size() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/api-keys?pageSize=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void page_size_101_returns_400_VALIDATION_ERROR_field_page_size() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/api-keys?pageSize=101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void garbage_cursor_returns_400_VALIDATION_ERROR_field_cursor() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/api-keys?cursor=not!valid!base64!")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/admin/api-keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private void seedApiKeys() {
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        apiKeyRepository.save(new ApiKey(new ClientId("svc-a"), BCRYPT_HASH, "a", false, t0));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-b"), BCRYPT_HASH, "b", false, t0.plusSeconds(1)));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-c"), BCRYPT_HASH, "c", false, t0.plusSeconds(2)));
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

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static Set<String> collectFieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
