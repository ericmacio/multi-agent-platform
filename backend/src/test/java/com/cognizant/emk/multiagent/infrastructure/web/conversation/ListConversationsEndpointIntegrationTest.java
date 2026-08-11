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
 * End-to-end integration test for {@code GET /conversations} (US-10-006).
 * Covers owner isolation, most-recent-first ordering, the optional
 * {@code agentId} filter (including unknown / cross-owner agent → empty),
 * cursor pagination, and the v1 SYSTEM-empty-page contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListConversationsEndpointIntegrationTest {

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
    void empty_list_returns_200_with_no_items() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    void owner_isolation_user_b_sees_no_user_a_conversations() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, now);
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, now.plusSeconds(1));

        String bobToken = login(mockMvc, BOB_EMAIL, BOB_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void list_is_ordered_most_recent_first() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationId c1 = seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base);
        ConversationId c2 = seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base.plusSeconds(1));
        ConversationId c3 = seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base.plusSeconds(2));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(c3.value().toString()))
                .andExpect(jsonPath("$.items[1].id").value(c2.value().toString()))
                .andExpect(jsonPath("$.items[2].id").value(c1.value().toString()));
    }

    @Test
    void cursor_pagination_walks_across_two_pages() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base);
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base.plusSeconds(1));
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0, base.plusSeconds(2));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        String firstBody = mockMvc.perform(get("/api/v1/conversations?pageSize=2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        String cursor = ConversationsEndpointTestSupport.extract(firstBody, "nextCursor");

        mockMvc.perform(get("/api/v1/conversations?pageSize=2&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void agent_id_filter_narrows_to_one_agent() throws Exception {
        AgentId agentX = seedAgent(agentRepository, aliceId, "agent-x");
        AgentId agentY = seedAgent(agentRepository, aliceId, "agent-y");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationId cX = seedConversation(conversationRepository, agentX, aliceId, null, 0, base);
        seedConversation(conversationRepository, agentY, aliceId, null, 0, base.plusSeconds(1));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations?agentId=" + agentX.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(cX.value().toString()))
                .andExpect(jsonPath("$.items[0].agentId").value(agentX.value().toString()));
    }

    @Test
    void agent_id_filter_with_unknown_agent_returns_empty_page_not_404() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        seedConversation(conversationRepository, aliceAgent, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations?agentId=" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void agent_id_filter_with_other_users_agent_returns_empty_page() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        seedConversation(conversationRepository, bobsAgent, bobId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/conversations?agentId=" + bobsAgent.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalid_page_size_returns_400_validation_error() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations?pageSize=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalid_cursor_returns_400_validation_error() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/conversations?cursor=!!!not-valid!!!")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
