package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.login;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedAgent;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedConversation;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /conversations/{conversationId}}
 * (US-10-007). Covers happy path, unknown id, cross-owner 404, SYSTEM
 * principal 404 against USER-owned conversation, and 401 unauthenticated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class GetConversationEndpointIntegrationTest {

    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private Flyway flyway;

    private UserId aliceId;
    private UserId bobId;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        aliceId = seedUser(userRepository, ALICE_EMAIL, ALICE_PASSWORD);
        bobId = seedUser(userRepository, BOB_EMAIL, BOB_PASSWORD);
    }

    @Test
    void owner_match_returns_200_with_conversation_shape() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                "planning session", 3, OffsetDateTime.now(ZoneOffset.UTC));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/{id}", convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convId.value().toString()))
                .andExpect(jsonPath("$.agentId").value(agentId.value().toString()))
                .andExpect(jsonPath("$.title").value("planning session"))
                .andExpect(jsonPath("$.messageCount").value(3));
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void cross_owner_returns_404_NOT_FOUND() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        ConversationId bobsConv = seedConversation(conversationRepository, bobsAgent, bobId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));

        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/{id}", bobsConv.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void system_caller_against_user_owned_conversation_returns_404() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, aliceAgent, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(), apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/conversations/{id}", convId.value())
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
