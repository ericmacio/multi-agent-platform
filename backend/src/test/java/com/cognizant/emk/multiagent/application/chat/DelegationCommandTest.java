package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationCommand;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationCommandTest {

    private static final AgentId PARENT = new AgentId(UUID.randomUUID());
    private static final AgentId TARGET = new AgentId(UUID.randomUUID());
    private static final UserId OWNER = new UserId(UUID.randomUUID());

    @Test
    void accepts_a_well_formed_command() {
        DelegationCommand cmd = new DelegationCommand(PARENT, OWNER, TARGET, "summarize this");

        assertThat(cmd.parentAgentId()).isEqualTo(PARENT);
        assertThat(cmd.parentOwner()).isEqualTo(OWNER);
        assertThat(cmd.targetMemberId()).isEqualTo(TARGET);
        assertThat(cmd.task()).isEqualTo("summarize this");
    }

    @Test
    void accepts_a_task_of_exactly_1024_chars() {
        new DelegationCommand(PARENT, OWNER, TARGET, "x".repeat(1024));
    }

    @Test
    void rejects_null_parent_agent_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DelegationCommand(null, OWNER, TARGET, "task"))
                .withMessage("parentAgentId");
    }

    @Test
    void rejects_null_parent_owner() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DelegationCommand(PARENT, null, TARGET, "task"))
                .withMessage("parentOwner");
    }

    @Test
    void rejects_null_target_member_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DelegationCommand(PARENT, OWNER, null, "task"))
                .withMessage("targetMemberId");
    }

    @Test
    void rejects_null_task() {
        assertThatThrownBy(() -> new DelegationCommand(PARENT, OWNER, TARGET, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("task"));
    }

    @Test
    void rejects_blank_task() {
        assertThatThrownBy(() -> new DelegationCommand(PARENT, OWNER, TARGET, "   "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("task"));
    }

    @Test
    void rejects_empty_task() {
        assertThatThrownBy(() -> new DelegationCommand(PARENT, OWNER, TARGET, ""))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("task"));
    }

    @Test
    void rejects_task_longer_than_1024_chars() {
        assertThatThrownBy(() -> new DelegationCommand(PARENT, OWNER, TARGET, "x".repeat(1025)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("task");
                    assertThat(ex.getMessage()).contains("1024");
                });
    }
}
