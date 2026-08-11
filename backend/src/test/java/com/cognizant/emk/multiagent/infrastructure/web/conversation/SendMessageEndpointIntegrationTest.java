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
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.login;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedAgent;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedConversation;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the streaming
 * {@code POST /conversations/{id}/messages} endpoint (US-11-007).
 *
 * <p>Uses a mocked {@link ChatModel} rather than WireMock — the OpenAI
 * autoconfig is excluded across the test suite (Spring AI 1.1.0 / Spring 7
 * binary incompat documented in {@code OpenAiChatClientAdapter}), so a
 * {@code @TestConfiguration} providing a {@code @Primary @Bean ChatModel}
 * mock is the right seam. {@code OpenAiChatClientAdapter}'s
 * {@code @ConditionalOnBean(ChatModel.class)} picks up the mock and wires
 * the {@code LlmChatClient} bean naturally; {@code SendMessageService}'s
 * {@code @ConditionalOnBean(LlmChatClient.class)} picks up the adapter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SendMessageEndpointIntegrationTest {

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
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private ChatModel chatModel;

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
        org.mockito.Mockito.reset(chatModel);
    }

    // ----- happy path -----

    @Test
    void happy_path_streams_started_three_deltas_completed_and_persists_both() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                responseWithText("Hel"),
                responseWithText("lo "),
                responseWithText("world")));

        String body = streamAndReadBody(convId, ALICE_EMAIL, ALICE_PASSWORD, "say hello");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).hasSize(5);  // started + 3 deltas + completed
        assertThat(frames.get(0).name()).isEqualTo("started");
        assertThat(frames.get(1).name()).isEqualTo("delta");
        assertThat(frames.get(1).data().get("text").asText()).isEqualTo("Hel");
        assertThat(frames.get(2).name()).isEqualTo("delta");
        assertThat(frames.get(3).name()).isEqualTo("delta");
        assertThat(frames.get(4).name()).isEqualTo("completed");
        assertThat(frames.get(4).data().get("title").asText()).isEqualTo("say hello");
        assertThat(frames.get(4).data().get("messageCount").asInt()).isEqualTo(2);

        // DB rows: 2 messages, count = 2.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(rows).isEqualTo(2);
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM conversations WHERE id = ?",
                Integer.class, convId.value());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void second_turn_completed_title_is_null_and_message_count_is_four() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, "existing-title", 2,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(responseWithText("hi")));

        String body = streamAndReadBody(convId, ALICE_EMAIL, ALICE_PASSWORD, "another");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        SseFrameParser.Frame completed = frames.get(frames.size() - 1);
        assertThat(completed.name()).isEqualTo("completed");
        assertThat(completed.data().get("title").isNull()).isTrue();
        assertThat(completed.data().get("messageCount").asInt()).isEqualTo(4);
    }

    @Test
    void empty_delta_chunk_is_elided_from_the_wire() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        // One empty chunk (elided) + one non-empty chunk = one delta frame on the wire.
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                responseWithText(""),
                responseWithText("ok")));

        String body = streamAndReadBody(convId, ALICE_EMAIL, ALICE_PASSWORD, "hi");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        long deltaCount = frames.stream().filter(f -> f.name().equals("delta")).count();
        assertThat(deltaCount).isEqualTo(1);
    }

    // ----- error frames -----

    @Test
    void llm_5xx_mid_stream_emits_started_delta_then_error_no_assistant_persisted() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.concat(
                Flux.just(responseWithText("partial")),
                Flux.error(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))));

        String body = streamAndReadBody(convId, ALICE_EMAIL, ALICE_PASSWORD, "hi");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).extracting(SseFrameParser.Frame::name)
                .containsExactly("started", "delta", "error");
        SseFrameParser.Frame error = frames.get(2);
        assertThat(error.data().get("code").asText()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(error.data().get("status").asInt()).isEqualTo(502);

        // DB: USER persisted, ASSISTANT not.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(rows).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM conversations WHERE id = ?",
                Integer.class, convId.value());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void llm_429_at_request_time_emits_started_then_error_llm_unavailable() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.error(
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)));

        String body = streamAndReadBody(convId, ALICE_EMAIL, ALICE_PASSWORD, "hi");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).extracting(SseFrameParser.Frame::name)
                .containsExactly("started", "error");
        assertThat(frames.get(1).data().get("code").asText())
                .as("provider 429 is treated as infrastructure failure, NOT our RATE_LIMITED")
                .isEqualTo("LLM_UNAVAILABLE");
    }

    // ----- synchronous-prefix failures (NOT text/event-stream responses) -----

    @Test
    void cap_reached_pre_flight_returns_409_conversation_full_json() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, "x", 64,
                OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"more\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONVERSATION_FULL"));
    }

    @Test
    void content_over_1024_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);
        String over = "a".repeat(1025);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + over + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("content")));
    }

    @Test
    void empty_content_returns_400_validation_error() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void accept_application_json_returns_406_not_acceptable() throws Exception {
        AgentId agentId = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, agentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId.value())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void cross_owner_returns_404_not_found() throws Exception {
        AgentId bobsAgent = seedAgent(agentRepository, bobId, "bobs-bot");
        ConversationId bobsConv = seedConversation(
                conversationRepository, bobsAgent, bobId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        String aliceToken = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", bobsConv.value())
                        .header("Authorization", "Bearer " + aliceToken)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknown_conversation_returns_404_not_found() throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", UUID.randomUUID())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void system_principal_against_user_owned_conversation_returns_404() throws Exception {
        AgentId aliceAgent = seedAgent(agentRepository, aliceId, "alice-bot");
        ConversationId convId = seedConversation(
                conversationRepository, aliceAgent, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId.value())
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ----- helpers -----

    /**
     * Sends the POST, awaits the async dispatch (SSE responses go through
     * Spring's async path), and returns the response body as a string. The
     * caller parses it via {@link SseFrameParser}.
     */
    private String streamAndReadBody(
            ConversationId convId, String email, String password, String content)
            throws Exception {
        String token = login(mockMvc, email, password);

        MvcResult initial = mockMvc.perform(
                        post("/api/v1/conversations/{id}/messages", convId.value())
                                .header("Authorization", "Bearer " + token)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"" + content + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        return dispatched.getResponse().getContentAsString();
    }

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /**
     * Supplies a mocked {@link ChatModel} AND a directly-built
     * {@code OpenAiChatClientAdapter} (the {@link com.cognizant.emk.multiagent
     * .application.chat.LlmChatClient} implementation) so that
     * {@code SendMessageService} has the {@code LlmChatClient} bean it needs
     * without depending on the timing of
     * {@code @ConditionalOnBean(ChatModel.class)} on the adapter, which is
     * unreliable when the ChatModel comes from a {@code @TestConfiguration}.
     * {@code @Primary} so the bean wins over any autoconfig path that ever
     * comes back.
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        ChatModel testChatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        @Primary
        com.cognizant.emk.multiagent.application.chat.LlmChatClient testLlmChatClient(
                ChatModel chatModel,
                com.cognizant.emk.multiagent.domain.tool.ToolCatalog toolCatalog,
                com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver mcpToolCallbackResolver) {
            return new com.cognizant.emk.multiagent.infrastructure.llm.openai
                    .OpenAiChatClientAdapter(chatModel, toolCatalog, mcpToolCallbackResolver);
        }
    }
}
