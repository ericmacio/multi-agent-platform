package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.application.chat.ChatTurnContext;
import com.cognizant.emk.multiagent.application.chat.DelegationService;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationCommand;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationResult;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.user.UserId;
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
class DelegateToolTest {

    @Mock private DelegationService delegationService;
    @Mock private ChatTurnContext chatTurnContext;

    private DelegateTool tool;

    private AgentId parentId;
    private UserId ownerId;
    private AgentId targetId;

    @BeforeEach
    void setUp() {
        tool = new DelegateTool(delegationService, chatTurnContext);
        parentId = new AgentId(UUID.randomUUID());
        ownerId = new UserId(UUID.randomUUID());
        targetId = new AgentId(UUID.randomUUID());
    }

    @Test
    void happy_path_returns_target_text_and_passes_parent_context_through() {
        when(chatTurnContext.parentAgentId()).thenReturn(parentId);
        when(chatTurnContext.parentOwner()).thenReturn(ownerId);
        when(delegationService.delegate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DelegationResult(targetId, "summary"));

        String result = tool.delegate(targetId.value().toString(), "summarize this");

        assertThat(result).isEqualTo("summary");

        ArgumentCaptor<DelegationCommand> captor = ArgumentCaptor.forClass(DelegationCommand.class);
        verify(delegationService).delegate(captor.capture());
        DelegationCommand cmd = captor.getValue();
        assertThat(cmd.parentAgentId()).isEqualTo(parentId);
        assertThat(cmd.parentOwner()).isEqualTo(ownerId);
        assertThat(cmd.targetMemberId()).isEqualTo(targetId);
        assertThat(cmd.task()).isEqualTo("summarize this");
    }

    @Test
    void empty_context_raises_illegal_state_before_calling_delegation_service() {
        when(chatTurnContext.parentAgentId()).thenThrow(new IllegalStateException(
                "ChatTurnContext.parentAgentId() invoked outside of a chat turn"));

        assertThatThrownBy(() -> tool.delegate(
                UUID.randomUUID().toString(), "anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ChatTurnContext");

        verify(delegationService, never()).delegate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformed_target_member_id_raises_illegal_argument_and_does_not_call_delegation_service() {
        when(chatTurnContext.parentAgentId()).thenReturn(parentId);

        assertThatThrownBy(() -> tool.delegate("not-a-uuid", "task"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetMemberId")
                .hasMessageContaining("not-a-uuid");

        verify(delegationService, never()).delegate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void null_target_member_id_raises_illegal_argument() {
        when(chatTurnContext.parentAgentId()).thenReturn(parentId);

        assertThatThrownBy(() -> tool.delegate(null, "task"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetMemberId");

        verify(delegationService, never()).delegate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blank_task_propagates_validation_exception_from_command_record() {
        when(chatTurnContext.parentAgentId()).thenReturn(parentId);
        when(chatTurnContext.parentOwner()).thenReturn(ownerId);

        assertThatThrownBy(() -> tool.delegate(targetId.value().toString(), "   "))
                .isInstanceOf(com.cognizant.emk.multiagent.domain.shared.ValidationException.class);

        verify(delegationService, never()).delegate(org.mockito.ArgumentMatchers.any());
    }
}
