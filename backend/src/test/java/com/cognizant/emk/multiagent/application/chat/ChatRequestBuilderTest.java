package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRequestBuilderTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ToolCatalog toolCatalog;
    @Mock private McpServerCatalog mcpServerCatalog;
    @Mock private FilesystemMcpUserScope filesystemMcpUserScope;

    private ChatRequestBuilder builder;

    private UserId ownerId;
    private AgentId agentId;

    @BeforeEach
    void setUp() {
        builder = new ChatRequestBuilder(
                agentRepository, toolCatalog, mcpServerCatalog,
                filesystemMcpUserScope, "gpt-4o-mini");
        ownerId = new UserId(UUID.randomUUID());
        agentId = new AgentId(UUID.randomUUID());
    }

    @Test
    void happy_path_translates_every_field() {
        Agent agent = sampleAgent()
                .withReplacement(
                        new AgentName("research-bot"),
                        "Searches the web",
                        "You are helpful",
                        new MemorySize(24),
                        new SamplingParams("gpt-4o", 0.7, 1024, 0.95),
                        List.of("AwsS3Tool"),
                        List.of("brave-search"),
                        Team.EMPTY,
                        OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        ToolDescriptor s3 = new ToolDescriptor("AwsS3Tool", "S3 access");
        when(toolCatalog.all()).thenReturn(List.of(s3));
        when(mcpServerCatalog.contains("brave-search")).thenReturn(true);

        List<Message> memory = List.of(
                msg(MessageRole.USER, "a", 1),
                msg(MessageRole.ASSISTANT, "b", 2),
                msg(MessageRole.USER, "c", 3));

        ChatRequest request = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), memory);

        assertThat(request.model()).isEqualTo("gpt-4o");
        assertThat(request.systemPrompt()).isEqualTo("You are helpful");
        assertThat(request.history()).hasSize(3);
        assertThat(request.history().get(0).role()).isEqualTo(Role.USER);
        assertThat(request.history().get(0).content()).isEqualTo("a");
        assertThat(request.history().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(request.history().get(2).content()).isEqualTo("c");
        assertThat(request.tools()).containsExactly(s3);
        assertThat(request.enabledMcpServers()).containsExactly("brave-search");
        assertThat(request.sampling().temperature()).isEqualTo(0.7);
        assertThat(request.sampling().maxOutputTokens()).isEqualTo(1024);
        assertThat(request.sampling().topP()).isEqualTo(0.95);
        assertThat(request.ownerUserId()).isEqualTo(ownerId.value());
    }

    @Test
    void falls_back_to_platform_default_model_when_agent_llm_model_is_null() {
        Agent agent = sampleAgent();  // SamplingParams.DEFAULTS = all-null
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        ChatRequest request = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        assertThat(request.model()).isEqualTo("gpt-4o-mini");  // from properties
    }

    @Test
    void agent_deleted_mid_turn_throws_agent_not_found() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of()))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void live_agent_mutation_is_observed_on_the_next_build_call() {
        // Build #1 with the original agent
        Agent v1 = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(v1));
        ChatRequest r1 = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());
        assertThat(r1.tools()).isEmpty();

        // Build #2 after the agent acquires a tool via PUT /agents/{id}
        Agent v2 = v1.withReplacement(
                v1.name(), v1.description(), v1.systemPrompt(),
                v1.memorySize(), v1.samplingParams(),
                List.of("AwsS3Tool"),
                List.of(), Team.EMPTY,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(v2));
        when(toolCatalog.all()).thenReturn(List.of(new ToolDescriptor("AwsS3Tool", "S3")));
        ChatRequest r2 = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        assertThat(r2.tools()).extracting(ToolDescriptor::name).containsExactly("AwsS3Tool");
    }

    @Test
    void filesystem_mcp_triggers_per_user_root_materialization() {
        Agent agent = sampleAgent().withReplacement(
                new AgentName("fs-bot"), "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of("filesystem"), Team.EMPTY,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(mcpServerCatalog.contains("filesystem")).thenReturn(true);
        when(filesystemMcpUserScope.resolveUserRoot(ownerId)).thenReturn(Paths.get("/tmp/x"));

        builder.build(agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        verify(filesystemMcpUserScope).resolveUserRoot(ownerId);
    }

    @Test
    void non_filesystem_agent_does_not_invoke_user_scope() {
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        builder.build(agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        verify(filesystemMcpUserScope, never()).resolveUserRoot(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknown_tool_drift_throws_agent_configuration_drift_exception() {
        Agent agent = sampleAgent().withReplacement(
                new AgentName("a"), "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of("DroppedTool"), List.of(), Team.EMPTY,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(toolCatalog.all()).thenReturn(List.of());  // catalog has no entries

        assertThatThrownBy(() -> builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of()))
                .isInstanceOf(AgentConfigurationDriftException.class)
                .hasMessageContaining("DroppedTool");
    }

    @Test
    void unknown_mcp_drift_throws_agent_configuration_drift_exception() {
        Agent agent = sampleAgent().withReplacement(
                new AgentName("a"), "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of("dropped-mcp"), Team.EMPTY,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(mcpServerCatalog.contains("dropped-mcp")).thenReturn(false);

        assertThatThrownBy(() -> builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of()))
                .isInstanceOf(AgentConfigurationDriftException.class)
                .hasMessageContaining("dropped-mcp");
    }

    @Test
    void agent_with_empty_team_has_no_delegate_descriptor_in_tools() {
        // US-12-003 runtime guarantee on top of REQ-AGT-013: leaf agents
        // never see the `delegate` descriptor — the LLM cannot call
        // delegate(...) for them.
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        ChatRequest request = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        assertThat(request.tools()).isEmpty();
        assertThat(request.tools())
                .extracting(ToolDescriptor::name)
                .doesNotContain(DelegationService.TOOL_NAME);
    }

    @Test
    void agent_with_non_empty_team_gets_delegate_descriptor_as_last_tool() {
        AgentId memberId = new AgentId(UUID.randomUUID());
        Agent agent = sampleAgent().withReplacement(
                new AgentName("parent"), "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(),
                new Team(List.of(memberId)),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        ChatRequest request = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().get(0)).isEqualTo(DelegationService.DESCRIPTOR);
    }

    @Test
    void agent_with_team_and_catalog_tools_gets_delegate_descriptor_appended_last() {
        AgentId memberId = new AgentId(UUID.randomUUID());
        Agent agent = sampleAgent().withReplacement(
                new AgentName("parent"), "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of("AwsS3Tool"), List.of(),
                new Team(List.of(memberId)),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        ToolDescriptor s3 = new ToolDescriptor("AwsS3Tool", "S3 access");
        when(toolCatalog.all()).thenReturn(List.of(s3));

        ChatRequest request = builder.build(
                agentId, new ConversationOwner.UserOwner(ownerId), List.of());

        assertThat(request.tools()).extracting(ToolDescriptor::name)
                .containsExactly("AwsS3Tool", DelegationService.TOOL_NAME);
    }

    @Test
    void system_owner_throws_illegal_state_in_v1() {
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> builder.build(
                agentId,
                new ConversationOwner.SystemOwner(new ClientId("svc-a")),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYSTEM");
    }

    // ----- helpers -----

    private Agent sampleAgent() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Agent(
                agentId, ownerId, new AgentName("a"),
                "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now);
    }

    private static Message msg(MessageRole role, String content, int sec) {
        ConversationId convId = new ConversationId(UUID.randomUUID());
        OffsetDateTime ts = OffsetDateTime.of(2026, 5, 1, 10, 0, sec, 0, ZoneOffset.UTC);
        return new Message(
                new MessageId(UUID.randomUUID()),
                convId, role, new MessageContent(content), ts);
    }
}
