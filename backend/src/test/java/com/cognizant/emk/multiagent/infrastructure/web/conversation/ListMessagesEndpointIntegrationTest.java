package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
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
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedMessage;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for
 * {@code GET /conversations/{conversationId}/messages} (US-10-010). Covers
 * empty page, 5-message keyset pagination in chronological-ASC order,
 * cross-owner 404, unknown-conversation 404, 400 on invalid params, 401
 * unauthenticated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListMessagesEndpointIntegrationTest {

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
    void empty_conversation_returns_200_with_no_items() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void five_messages_paginate_in_chronological_ascending_order() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 5, base);
        MessageId m1 = seedMessage(conversationRepository, convId, MessageRole.USER,      "1", base.plusSeconds(1));
        MessageId m2 = seedMessage(conversationRepository, convId, MessageRole.ASSISTANT, "2", base.plusSeconds(2));
        MessageId m3 = seedMessage(conversationRepository, convId, MessageRole.USER,      "3", base.plusSeconds(3));
        MessageId m4 = seedMessage(conversationRepository, convId, MessageRole.ASSISTANT, "4", base.plusSeconds(4));
        MessageId m5 = seedMessage(conversationRepository, convId, MessageRole.USER,      "5", base.plusSeconds(5));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        // First page: 2 messages + cursor
        String firstBody = mockMvc.perform(get("/api/v1/conversations/{id}/messages?pageSize=2",
                                convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(m1.value().toString()))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[0].content").value("1"))
                .andExpect(jsonPath("$.items[1].id").value(m2.value().toString()))
                .andExpect(jsonPath("$.items[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        String cursor1 = ConversationsEndpointTestSupport.extract(firstBody, "nextCursor");

        // Second page: next 2 + cursor
        String secondBody = mockMvc.perform(get("/api/v1/conversations/{id}/messages?pageSize=2&cursor="
                                + cursor1, convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(m3.value().toString()))
                .andExpect(jsonPath("$.items[1].id").value(m4.value().toString()))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        String cursor2 = ConversationsEndpointTestSupport.extract(secondBody, "nextCursor");

        // Third page: last 1 + no cursor
        mockMvc.perform(get("/api/v1/conversations/{id}/messages?pageSize=2&cursor=" + cursor2,
                                convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(m5.value().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void cross_owner_returns_404_NOT_FOUND() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        ConversationId bobsConv = seedConversation(conversationRepository, bobsAgent, bobId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations/{id}/messages", bobsConv.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknown_conversation_returns_404_NOT_FOUND() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalid_cursor_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations/{id}/messages?cursor=!!!bad!!!", convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalid_page_size_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations/{id}/messages?pageSize=101", convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
