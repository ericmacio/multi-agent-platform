package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.login;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedAgent;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedConversation;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedMessage;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for
 * {@code DELETE /conversations/{conversationId}} (US-10-009). Covers happy
 * path, cascade through messages via the REST path (defense in depth on top
 * of EPIC-02 schema-level cascade), unknown id 404, cross-owner 404, 401
 * unauthenticated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DeleteConversationEndpointIntegrationTest {

    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;

    private UserId aliceId;
    private UserId bobId;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        aliceId = seedUser(userRepository, ALICE_EMAIL, ALICE_PASSWORD);
        bobId = seedUser(userRepository, BOB_EMAIL, BOB_PASSWORD);
    }

    @Test
    void owner_match_returns_204_and_deletes_conversation_and_cascades_messages()
            throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationId convId = seedConversation(conversationRepository, agentId, aliceId,
                null, 0, base);
        seedMessage(conversationRepository, convId, MessageRole.USER, "hi", base.plusSeconds(1));
        seedMessage(conversationRepository, convId, MessageRole.ASSISTANT, "hello", base.plusSeconds(2));
        seedMessage(conversationRepository, convId, MessageRole.USER, "bye", base.plusSeconds(3));

        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(delete("/api/v1/conversations/{id}", convId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        Integer convCount = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?",
                Integer.class, convId.value());
        Integer msgCount = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(convCount).isZero();
        assertThat(msgCount).as("FK cascade must remove the 3 messages too").isZero();
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(delete("/api/v1/conversations/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void cross_owner_returns_404_NOT_FOUND_and_does_not_delete() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        ConversationId bobsConv = seedConversation(conversationRepository, bobsAgent, bobId,
                null, 0, OffsetDateTime.now(ZoneOffset.UTC));

        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(delete("/api/v1/conversations/{id}", bobsConv.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?",
                Integer.class, bobsConv.value());
        assertThat(count).isOne();
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(delete("/api/v1/conversations/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
