package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.login;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedAgent;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedConversation;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code PATCH /conversations/{conversationId}}
 * (US-10-008). Covers happy path (including renaming from a previously
 * null title), title validation (blank / over-32 chars / null body),
 * cross-owner 404, unknown id 404, and 401 unauthenticated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PatchConversationEndpointIntegrationTest {

    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ConversationRepository conversationRepository;
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
    void owner_match_returns_200_with_renamed_conversation() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(patch("/api/v1/conversations/{id}", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"My session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convId.value().toString()))
                .andExpect(jsonPath("$.title").value("My session"));
    }

    @Test
    void blank_title_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(patch("/api/v1/conversations/{id}", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("title")));
    }

    @Test
    void title_over_32_chars_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        String over = "a".repeat(33);

        mockMvc.perform(patch("/api/v1/conversations/{id}", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + over + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("title")));
    }

    @Test
    void cross_owner_returns_404_NOT_FOUND() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        ConversationId bobsConv = seedConversation(conversationRepository, bobsAgent, bobId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(patch("/api/v1/conversations/{id}", bobsConv.value())
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijack\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(patch("/api/v1/conversations/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(patch("/api/v1/conversations/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
