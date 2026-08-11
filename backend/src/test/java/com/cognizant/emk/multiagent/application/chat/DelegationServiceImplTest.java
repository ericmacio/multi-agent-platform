package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationCommand;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationResult;
import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.InvalidDelegationTargetException;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelegationServiceImplTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ToolCatalog toolCatalog;
    @Mock private McpServerCatalog mcpServerCatalog;
    @Mock private FilesystemMcpUserScope filesystemMcpUserScope;
    @Mock private LlmChatClient llmChatClient;

    private DelegationServiceImpl service;

    private UserId ownerId;
    private AgentId parentId;
    private AgentId targetId;

    @BeforeEach
    void setUp() {
        service = new DelegationServiceImpl(
                agentRepository, toolCatalog, mcpServerCatalog,
                filesystemMcpUserScope, Optional.of(llmChatClient), "gpt-4o-mini");
        ownerId = new UserId(UUID.randomUUID());
        parentId = new AgentId(UUID.randomUUID());
        targetId = new AgentId(UUID.randomUUID());
    }

    @Test
    void happy_path_builds_minimal_target_request_and_returns_result() {
        Agent parent = agent(parentId, "you may delegate", new Team(List.of(targetId)));
        Agent target = agent(targetId, "you are B", Team.EMPTY);
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(llmChatClient.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChatResult("hello from B"));

        DelegationResult result = service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "summarize this"));

        assertThat(result.targetMemberId()).isEqualTo(targetId);
        assertThat(result.text()).isEqualTo("hello from B");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).call(captor.capture());
        ChatRequest req = captor.getValue();
        assertThat(req.systemPrompt()).isEqualTo("you are B");
        assertThat(req.history()).hasSize(1);
        assertThat(req.history().get(0).role()).isEqualTo(Role.USER);
        assertThat(req.history().get(0).content()).isEqualTo("summarize this");
        assertThat(req.tools()).isEmpty();
        assertThat(req.enabledMcpServers()).isEmpty();
        assertThat(req.ownerUserId()).isEqualTo(ownerId.value());
        assertThat(req.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void target_tools_and_mcps_are_resolved_against_the_catalogs() {
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        Agent target = agent(targetId, "t",
                List.of("AwsS3Tool"), List.of("brave-search"), Team.EMPTY,
                SamplingParams.DEFAULTS);
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(target));
        ToolDescriptor s3 = new ToolDescriptor("AwsS3Tool", "S3 access");
        when(toolCatalog.all()).thenReturn(List.of(s3));
        when(mcpServerCatalog.contains("brave-search")).thenReturn(true);
        when(llmChatClient.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChatResult("ok"));

        service.delegate(new DelegationCommand(parentId, ownerId, targetId, "go"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).call(captor.capture());
        assertThat(captor.getValue().tools()).containsExactly(s3);
        assertThat(captor.getValue().enabledMcpServers()).containsExactly("brave-search");
    }

    @Test
    void filesystem_mcp_materialization_uses_parent_owner_as_principal() {
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        Agent target = agent(targetId, "t",
                List.of(), List.of("filesystem"), Team.EMPTY,
                SamplingParams.DEFAULTS);
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(mcpServerCatalog.contains("filesystem")).thenReturn(true);
        when(filesystemMcpUserScope.resolveUserRoot(ownerId)).thenReturn(Paths.get("/tmp/x"));
        when(llmChatClient.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChatResult("ok"));

        service.delegate(new DelegationCommand(parentId, ownerId, targetId, "go"));

        verify(filesystemMcpUserScope).resolveUserRoot(ownerId);
    }

    @Test
    void parent_vanished_throws_invalid_delegation_target() {
        when(agentRepository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "go")))
                .isInstanceOf(InvalidDelegationTargetException.class)
                .hasMessageContaining(parentId.value().toString())
                .hasMessageContaining("parent agent not found");

        verify(llmChatClient, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void target_not_in_parents_team_throws_and_skips_llm() {
        AgentId other = new AgentId(UUID.randomUUID());
        Agent parent = agent(parentId, "p", new Team(List.of(other)));
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "go")))
                .isInstanceOf(InvalidDelegationTargetException.class)
                .hasMessageContaining("not a member");

        verify(agentRepository, never()).findById(targetId);
        verify(llmChatClient, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void target_with_non_empty_team_throws_and_does_not_leak_nested_member_ids() {
        AgentId nested = new AgentId(UUID.randomUUID());
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        Agent targetWithTeam = agent(targetId, "t", new Team(List.of(nested)));
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(targetWithTeam));

        assertThatThrownBy(() -> service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "go")))
                .isInstanceOfSatisfying(InvalidDelegationTargetException.class, ex -> {
                    assertThat(ex.getMessage())
                            .contains(targetId.value().toString())
                            .doesNotContain(nested.value().toString());
                });

        verify(llmChatClient, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void target_owned_by_different_user_throws_invalid_delegation_target() {
        UserId otherOwner = new UserId(UUID.randomUUID());
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        Agent targetDifferentOwner = new Agent(
                targetId, otherOwner, new AgentName("t"),
                "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now(), now());
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(targetDifferentOwner));

        assertThatThrownBy(() -> service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "go")))
                .isInstanceOf(InvalidDelegationTargetException.class)
                .hasMessageContaining("different owner");

        verify(llmChatClient, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void target_resolution_failure_throws_invalid_delegation_target() {
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delegate(new DelegationCommand(
                parentId, ownerId, targetId, "go")))
                .isInstanceOf(InvalidDelegationTargetException.class)
                .hasMessageContaining("target agent not found");

        verify(llmChatClient, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void target_model_override_takes_precedence_over_platform_default() {
        Agent parent = agent(parentId, "p", new Team(List.of(targetId)));
        Agent target = agent(targetId, "t",
                List.of(), List.of(), Team.EMPTY,
                new SamplingParams("gpt-4o", null, null, null));
        when(agentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(agentRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(llmChatClient.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChatResult("ok"));

        service.delegate(new DelegationCommand(parentId, ownerId, targetId, "go"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmChatClient).call(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-4o");
    }

    @Test
    void impl_has_no_conversation_repository_field() throws Exception {
        // REQ-AGT-015 load-bearing guarantee: the class has no path to persist
        // sub-agent turns. The ArchUnit rule
        // `delegation_service_impl_does_not_depend_on_conversation_repository`
        // is the build-time enforcement; this reflection check is the unit-test
        // mirror so a refactor that adds the field fails fast in this test
        // alongside the ArchUnit rule.
        for (Field field : DelegationServiceImpl.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("field %s on DelegationServiceImpl", field.getName())
                    .isNotEqualTo(ConversationRepository.class);
        }
    }

    // ----- helpers -----

    private Agent agent(AgentId id, String systemPrompt, Team team) {
        return agent(id, systemPrompt, List.of(), List.of(), team, SamplingParams.DEFAULTS);
    }

    private Agent agent(
            AgentId id,
            String systemPrompt,
            List<String> tools,
            List<String> mcps,
            Team team,
            SamplingParams sampling) {
        return new Agent(
                id, ownerId, new AgentName("a-" + id.value().toString().substring(0, 4)),
                "d", systemPrompt,
                MemorySize.DEFAULT, sampling,
                tools, mcps, team,
                now(), now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
