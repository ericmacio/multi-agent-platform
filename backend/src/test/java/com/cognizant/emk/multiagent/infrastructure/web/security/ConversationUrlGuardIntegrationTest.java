package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code /api/v1/conversations/**} URL guard introduced by
 * US-10-005: STANDARD, ADMIN, AND SYSTEM principals are admitted (the only
 * feature surface where SYSTEM may reach), while unauthenticated callers
 * 401 — never a 302 / form-login redirect.
 *
 * <p>SYSTEM admission is proven by observing a {@code 404 NOT_FOUND} on
 * {@code POST /conversations} (the URL guard passed; the v1 SYSTEM-can't-own
 * -agents rule produced the 404 from {@code StartConversationService}).
 * A 403 would mean the URL guard was misconfigured.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ConversationUrlGuardIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String STANDARD_EMAIL = "standard@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private Flyway flyway;

    private UserId standardUserId;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        standardUserId = seedUser(STANDARD_EMAIL, STANDARD_PASSWORD);
    }

    @Test
    void unauthenticated_post_returns_401_not_302() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void standard_jwt_is_admitted_through_the_url_guard() throws Exception {
        // STANDARD reaches the controller; the service then 404s because no
        // agent exists. A 403 here would mean the URL guard rejected the
        // JWT — which would be the regression we are guarding against.
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void admin_jwt_is_admitted_through_the_url_guard() throws Exception {
        // The seeded bootstrap admin can authenticate; same pattern as STANDARD.
        seedAdminWithKnownPassword();
        String token = login("admin@example.test", "Admin!23456");

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void system_api_key_caller_is_admitted_through_the_url_guard() throws Exception {
        // SYSTEM passes the URL guard; the controller / service then 404s
        // because no agent is SYSTEM-owned in v1. 403 here would be a URL-guard
        // regression — the load-bearing assertion is the absence of 403.
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));
        AgentId aliceAgent = persistAgentFor(standardUserId, "alice-bot");

        mockMvc.perform(post("/api/v1/conversations")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + aliceAgent.value() + "\"}"))
                .andExpect(status().isNotFound())                       // NOT 403
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
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

    private void seedAdminWithKnownPassword() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.save(new User(
                new UserId(UUID.randomUUID()),
                new Email("admin@example.test"),
                BCRYPT.encode("Admin!23456"),
                Role.ADMIN, false, false, now, now));
    }

    private AgentId persistAgentFor(UserId owner, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now));
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
