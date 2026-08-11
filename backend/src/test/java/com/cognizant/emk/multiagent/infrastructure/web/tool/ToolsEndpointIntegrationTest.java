package com.cognizant.emk.multiagent.infrastructure.web.tool;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /tools} (US-07-004). Uses the dev profile
 * so both the production {@code AwsS3Tool} and the dev-only {@code TestToolFixture} are
 * in the catalog.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ToolsEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private Flyway flyway;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        seedUser(STANDARD_EMAIL, STANDARD_PASSWORD);
    }

    @Test
    void standard_jwt_returns_the_catalog_including_AwsS3Tool() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(get("/api/v1/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.hasItem("AwsS3Tool")))
                .andExpect(jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.hasItem("TestTool")));
    }

    @Test
    void system_api_key_returns_the_same_catalog() throws Exception {
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/tools")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.hasItem("AwsS3Tool")));
    }

    @Test
    void successive_GETs_return_the_same_deterministic_body() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        MvcResult first = mockMvc.perform(get("/api/v1/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(first.getResponse().getContentAsString())
                .isEqualTo(second.getResponse().getContentAsString());
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private UserId seedUser(String email, String password) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), BCRYPT.encode(password),
                Role.STANDARD, false, false, now, now));
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
        if (start < 0) throw new AssertionError("field '" + field + "' not found in: " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
