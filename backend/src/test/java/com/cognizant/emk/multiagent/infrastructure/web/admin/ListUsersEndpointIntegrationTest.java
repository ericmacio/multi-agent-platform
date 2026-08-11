package com.cognizant.emk.multiagent.infrastructure.web.admin;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /admin/users} (US-05-005). Asserts pagination,
 * the metadata-only invariant (no {@code passwordHash} in any item), validation, and
 * authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListUsersEndpointIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
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

    // ------- pagination -------

    @Test
    void paginates_across_two_pages_when_size_2_and_three_users_after_seeded_admin() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedThreeUsersStrictlyNewerThanAdmin();

        String firstBody = mockMvc.perform(get("/api/v1/admin/users?pageSize=2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].email").value("c@example.test"))
                .andExpect(jsonPath("$.items[1].email").value("b@example.test"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.pageSize").value(2))
                .andReturn().getResponse().getContentAsString();

        String cursor = extract(firstBody, "nextCursor");
        mockMvc.perform(get("/api/v1/admin/users?pageSize=2&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].email").value("a@example.test"))
                .andExpect(jsonPath("$.items[1].email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.pageSize").value(2));
    }

    @Test
    void response_items_carry_only_metadata_no_password_hash() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        seedThreeUsersStrictlyNewerThanAdmin();

        String body = mockMvc.perform(get("/api/v1/admin/users")
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
            assertThat(fields).containsExactlyInAnyOrder(
                    "id", "email", "role", "disabled", "mustChangePassword",
                    "createdAt", "updatedAt");
            assertThat(item.get("passwordHash")).isNull();
        }
        // Defense in depth: the raw JSON body contains no hash markers.
        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$10$");
    }

    // ------- validation -------

    @Test
    void page_size_zero_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/users?pageSize=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void page_size_101_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/users?pageSize=101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void garbage_cursor_returns_400_field_cursor() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/users?cursor=not!valid!base64!")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    // ------- authorization -------

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        seedStandardUser();
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private void seedThreeUsersStrictlyNewerThanAdmin() {
        // The seeded admin's createdAt is "now" at Flyway migrate time. Insert three
        // users with strictly later timestamps so the seeded admin lands at the bottom
        // of the DESC order and the page positions are deterministic.
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(10)
                .truncatedTo(ChronoUnit.MILLIS);
        saveUser("a@example.test", base);
        saveUser("b@example.test", base.plusSeconds(1));
        saveUser("c@example.test", base.plusSeconds(2));
    }

    private void saveUser(String email, OffsetDateTime when) {
        userRepository.save(new User(
                new UserId(UUID.randomUUID()),
                new Email(email),
                SAMPLE_HASH,
                Role.STANDARD,
                false,
                false,
                when,
                when));
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
