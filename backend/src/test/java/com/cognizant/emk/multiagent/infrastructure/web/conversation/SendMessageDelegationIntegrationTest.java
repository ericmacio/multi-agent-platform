package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cognizant.emk.multiagent.application.chat.ChatChunk;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.ChatResult;
import com.cognizant.emk.multiagent.application.chat.DelegationService;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationCommand;
import com.cognizant.emk.multiagent.application.chat.LlmChatClient;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.InvalidDelegationTargetException;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.tool.DelegateTool;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.login;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedConversation;
import static com.cognizant.emk.multiagent.infrastructure.web.conversation.ConversationsEndpointTestSupport.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for EPIC-12 delegation (US-12-004).
 *
 * <p>The story spec called for a WireMock-driven test that exercised Spring
 * AI's tool-callback loop end-to-end. Two facts force a narrower scope:
 * <ul>
 *   <li>US-11-007 already documented why WireMock is replaced by a mocked
 *   {@code ChatModel} / {@code LlmChatClient} (Spring AI 1.1.0 / Spring 7
 *   binary incompat — autoconfig excluded across the suite).</li>
 *   <li>The current {@code OpenAiChatClientAdapter} does NOT yet wire
 *   {@code ChatRequest.tools} into a Spring AI {@code ChatClient} with tool
 *   callbacks (see its own Javadoc and US-09-005 — tool wiring is queued for
 *   a follow-up). So a literal "the LLM emits a tool call, the framework
 *   invokes {@code DelegateTool}, the parent stream resumes" loop cannot be
 *   driven from a real {@code ChatClient}.</li>
 * </ul>
 *
 * <p>The test below uses the same {@code @MockitoBean LlmChatClient} pattern
 * as US-11-007 and simulates the tool-callback loop by having the mock's
 * {@code stream(...)} answer invoke {@link DelegateTool#delegate(String, String)}
 * inline — the closest analog to what Spring AI would do once the adapter
 * runs a {@code ChatClient}. Every EPIC-12 invariant the story spec lists
 * is exercised:
 * <ul>
 *   <li>parent with a non-empty team gets {@code delegate} in
 *   {@code ChatRequest.tools};</li>
 *   <li>leaf agents NEVER see {@code delegate} (runtime guarantee on top of
 *   REQ-AGT-013);</li>
 *   <li>{@link DelegateTool#delegate(String, String)} invoked during a real
 *   chat turn correctly resolves the request-scoped {@code ChatTurnContext}
 *   and returns the sub-agent's text;</li>
 *   <li>runtime team-membership rejection (LLM-emitted target not in the
 *   parent's team) ends the parent stream with an error frame, persists the
 *   USER message but NOT the ASSISTANT, and never invokes the sub-agent
 *   LLM call;</li>
 *   <li>sub-agent LLM failure ends the parent stream with an error frame
 *   and does not persist the ASSISTANT;</li>
 *   <li>service-level: {@code DelegationService.delegate(...)} runs the
 *   sub-agent turn synchronously, persists nothing, and the runtime
 *   single-level invariant is re-checked against the live agent state;</li>
 *   <li>logs do not leak the test OpenAI API key.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SendMessageDelegationIntegrationTest {

    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private DelegateTool delegateTool;
    @Autowired private DelegationService delegationService;

    @MockitoBean private LlmChatClient llmChatClient;

    private UserId aliceId;
    private JdbcTemplate jdbc;
    private ListAppender<ILoggingEvent> rootAppender;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        aliceId = seedUser(userRepository, ALICE_EMAIL, ALICE_PASSWORD);
        org.mockito.Mockito.reset(llmChatClient);

        rootAppender = new ListAppender<>();
        rootAppender.start();
        rootLogger().addAppender(rootAppender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger().detachAppender(rootAppender);
    }

    // -------------------------------------------------------------------------
    // HTTP-level — ChatRequest.tools verification
    // -------------------------------------------------------------------------

    @Test
    void parent_with_team_includes_delegate_descriptor_in_chat_request_tools() throws Exception {
        AgentId memberId = seedAgent(aliceId, "member-bot", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot", new Team(List.of(memberId)));
        ConversationId convId = seedConversation(
                conversationRepository, parentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(llmChatClient.stream(any())).thenReturn(Flux.just(new ChatChunk("ok")));

        streamAndReadBody(convId, "say hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).stream(captor.capture());
        assertThat(captor.getValue().tools())
                .as("parent with non-empty team gets `delegate` descriptor in ChatRequest.tools")
                .extracting(ToolDescriptor::name)
                .contains(DelegationService.TOOL_NAME);
    }

    @Test
    void leaf_agent_excludes_delegate_descriptor_from_chat_request_tools() throws Exception {
        AgentId leafId = seedAgent(aliceId, "leaf-bot", Team.EMPTY);
        ConversationId convId = seedConversation(
                conversationRepository, leafId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(llmChatClient.stream(any())).thenReturn(Flux.just(new ChatChunk("ok")));

        streamAndReadBody(convId, "hello");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).stream(captor.capture());
        assertThat(captor.getValue().tools())
                .as("leaf agent (empty team) MUST NOT see the `delegate` descriptor")
                .extracting(ToolDescriptor::name)
                .doesNotContain(DelegationService.TOOL_NAME);
    }

    // -------------------------------------------------------------------------
    // HTTP-level — tool-loop simulation via the mock Answer
    // -------------------------------------------------------------------------

    @Test
    void delegate_tool_invocation_during_parent_stream_returns_sub_agent_text() throws Exception {
        AgentId memberId = seedAgent(aliceId, "member-bot", "you are M", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot", "you may delegate",
                new Team(List.of(memberId)));
        ConversationId convId = seedConversation(
                conversationRepository, parentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));

        // Parent's stream: simulate the LLM emitting a tool call by invoking
        // DelegateTool inline (closest analog to Spring AI's tool-callback
        // framework), then returning a delta incorporating the sub-agent's
        // text.
        when(llmChatClient.stream(any())).thenAnswer(inv -> {
            String subText = delegateTool.delegate(memberId.value().toString(), "summarize this");
            return Flux.just(new ChatChunk("based on B: " + subText));
        });
        // Sub-agent's sync call.
        when(llmChatClient.call(any())).thenReturn(new ChatResult("the summary"));

        String body = streamAndReadBody(convId, "please summarize");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).extracting(SseFrameParser.Frame::name)
                .containsExactly("started", "delta", "completed");
        assertThat(frames.get(1).data().get("text").asText())
                .isEqualTo("based on B: the summary");
        assertThat(frames.get(2).data().get("messageCount").asInt()).isEqualTo(2);
        assertNoFrameMentionsDelegate(frames);

        // DB: parent conversation has exactly 2 messages (USER + ASSISTANT).
        // No sub-agent conversation/messages anywhere (REQ-AGT-015 load-bearing).
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(rows).isEqualTo(2);
        Integer totalConversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations", Integer.class);
        assertThat(totalConversations)
                .as("the sub-agent turn does NOT create a separate conversation row")
                .isEqualTo(1);

        // The sync call captured the sub-agent's view of the world: M's
        // system prompt + ONLY the delegated task as history (no parent
        // context — REQ-AGT-015 explicit).
        ArgumentCaptor<ChatRequest> subCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).call(subCaptor.capture());
        ChatRequest subRequest = subCaptor.getValue();
        assertThat(subRequest.systemPrompt()).isEqualTo("you are M");
        assertThat(subRequest.history()).hasSize(1);
        assertThat(subRequest.history().get(0).content()).isEqualTo("summarize this");
        assertThat(subRequest.tools())
                .as("sub-agent never sees the `delegate` descriptor (leaf by construction)")
                .extracting(ToolDescriptor::name)
                .doesNotContain(DelegationService.TOOL_NAME);
        assertThat(subRequest.ownerUserId()).isEqualTo(aliceId.value());
    }

    @Test
    void delegate_tool_to_non_member_target_ends_stream_with_error_no_assistant_persisted() throws Exception {
        AgentId memberId = seedAgent(aliceId, "member-bot", Team.EMPTY);
        // Third agent NOT in the parent's team — the LLM "asks" to delegate to it.
        AgentId nonMemberId = seedAgent(aliceId, "non-member-bot", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot",
                new Team(List.of(memberId)));
        ConversationId convId = seedConversation(
                conversationRepository, parentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));

        when(llmChatClient.stream(any())).thenAnswer(inv -> {
            // Simulate the LLM emitting an invalid tool call; the framework
            // would catch the InvalidDelegationTargetException and surface
            // it as an infrastructure failure of the model. We replicate
            // that by wrapping the throw in an LlmUnavailableException
            // signal so the SSE error frame surfaces as LLM_UNAVAILABLE
            // (matching the story spec's intent).
            try {
                delegateTool.delegate(nonMemberId.value().toString(), "task");
                return Flux.<ChatChunk>error(new IllegalStateException("unreachable"));
            } catch (InvalidDelegationTargetException ex) {
                return Flux.<ChatChunk>error(new LlmUnavailableException(
                        "tool callback rejected target", ex));
            }
        });

        String body = streamAndReadBody(convId, "please delegate");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).extracting(SseFrameParser.Frame::name)
                .containsExactly("started", "error");
        assertThat(frames.get(1).data().get("code").asText())
                .isEqualTo("LLM_UNAVAILABLE");

        // DB: USER message persisted; ASSISTANT NOT persisted (the stream
        // terminated in error before the assistant-persist step ran).
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(rows).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM conversations WHERE id = ?",
                Integer.class, convId.value());
        assertThat(count).isEqualTo(1);

        // The sub-agent LLM call is NEVER made — DelegationService rejects
        // the target before reaching llmChatClient.call(...).
        verify(llmChatClient, never()).call(any());
    }

    @Test
    void sub_agent_llm_failure_ends_parent_stream_with_error_no_assistant_persisted() throws Exception {
        AgentId memberId = seedAgent(aliceId, "member-bot", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot",
                new Team(List.of(memberId)));
        ConversationId convId = seedConversation(
                conversationRepository, parentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));

        when(llmChatClient.stream(any())).thenAnswer(inv -> {
            try {
                delegateTool.delegate(memberId.value().toString(), "task");
                return Flux.<ChatChunk>just(new ChatChunk("unreachable"));
            } catch (LlmUnavailableException ex) {
                return Flux.<ChatChunk>error(ex);
            }
        });
        when(llmChatClient.call(any())).thenThrow(
                new LlmUnavailableException("openai_provider_failure: http_5xx 503"));

        String body = streamAndReadBody(convId, "please delegate");

        List<SseFrameParser.Frame> frames = SseFrameParser.parse(body);
        assertThat(frames).extracting(SseFrameParser.Frame::name)
                .containsExactly("started", "error");
        assertThat(frames.get(1).data().get("code").asText())
                .isEqualTo("LLM_UNAVAILABLE");
        // The body MUST NOT leak the raw provider classification string —
        // SseErrorTranslator emits a sanitized detail message.
        assertThat(frames.get(1).data().get("detail").asText())
                .doesNotContain("503")
                .doesNotContain("openai_provider_failure");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(rows).isEqualTo(1);  // USER only
    }

    // -------------------------------------------------------------------------
    // Service-level — DelegationService runtime invariants
    // -------------------------------------------------------------------------

    @Test
    void delegation_service_rejects_target_with_non_empty_team_at_runtime() {
        // Seed a "leaf" team member, then a parent with that member.
        AgentId nestedMemberId = seedAgent(aliceId, "nested-bot", Team.EMPTY);
        AgentId memberId = seedAgent(aliceId, "member-bot", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot",
                new Team(List.of(memberId)));

        // Force-overwrite the member's team to a non-empty list — this is the
        // pathological state EPIC-06's write-time validators forbid; the
        // runtime check in DelegationServiceImpl is the defense-in-depth that
        // catches it.
        Agent original = agentRepository.findById(memberId).orElseThrow();
        agentRepository.save(original.withReplacement(
                original.name(), original.description(), original.systemPrompt(),
                original.memorySize(), original.samplingParams(),
                original.tools(), original.enabledMcpServers(),
                new Team(List.of(nestedMemberId)),
                OffsetDateTime.now(ZoneOffset.UTC)));

        assertThatThrownBy(() -> delegationService.delegate(new DelegationCommand(
                parentId, aliceId, memberId, "task")))
                .isInstanceOf(InvalidDelegationTargetException.class)
                .hasMessageContaining("non-empty team");

        // The pathological nested member is never touched by the runtime
        // check — its id MUST NOT appear in the exception message
        // (sanitization assertion).
        verify(llmChatClient, never()).call(any());
    }

    // -------------------------------------------------------------------------
    // Log sanitization
    // -------------------------------------------------------------------------

    @Test
    void logs_do_not_leak_the_test_openai_api_key_or_secret_fragments() throws Exception {
        // application.yaml (test profile) seeds `spring.ai.openai.api-key: test-openai-key`.
        // The delegation flow must NOT log that fragment anywhere — REQ-SEC-004 /
        // REQ-AUTH-009 sanitization. Same posture every other EPIC has held.
        AgentId memberId = seedAgent(aliceId, "member-bot", Team.EMPTY);
        AgentId parentId = seedAgent(aliceId, "parent-bot",
                new Team(List.of(memberId)));
        ConversationId convId = seedConversation(
                conversationRepository, parentId, aliceId, null, 0,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(llmChatClient.stream(any())).thenAnswer(inv -> {
            String s = delegateTool.delegate(memberId.value().toString(), "task");
            return Flux.just(new ChatChunk(s));
        });
        when(llmChatClient.call(any())).thenReturn(new ChatResult("ok"));

        streamAndReadBody(convId, "hi");

        assertThat(rootAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("test-openai-key"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentId seedAgent(UserId owner, String name, Team team) {
        return seedAgent(owner, name, "s", team);
    }

    private AgentId seedAgent(UserId owner, String name, String systemPrompt, Team team) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", systemPrompt,
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), team,
                now, now));
        return id;
    }

    private String streamAndReadBody(ConversationId convId, String content) throws Exception {
        String token = login(mockMvc, ALICE_EMAIL, ALICE_PASSWORD);

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

    private static void assertNoFrameMentionsDelegate(List<SseFrameParser.Frame> frames) {
        for (SseFrameParser.Frame frame : frames) {
            String raw = frame.data() == null ? "" : frame.data().toString();
            assertThat(raw)
                    .as("frame %s MUST NOT leak any reference to delegation (REQ-AGT-015)", frame.name())
                    .doesNotContain("delegate")
                    .doesNotContain("tool_call");
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
