package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidDelegationTargetExceptionTest {

    @Test
    void message_contains_both_uuids_and_no_agent_names() {
        AgentId parent = new AgentId(UUID.randomUUID());
        AgentId target = new AgentId(UUID.randomUUID());

        InvalidDelegationTargetException ex =
                new InvalidDelegationTargetException(parent, target, "target not in team");

        assertThat(ex.getMessage())
                .contains(parent.value().toString())
                .contains(target.value().toString())
                .contains("target not in team");
    }

    @Test
    void carries_typed_accessors_for_both_ids() {
        AgentId parent = new AgentId(UUID.randomUUID());
        AgentId target = new AgentId(UUID.randomUUID());

        InvalidDelegationTargetException ex =
                new InvalidDelegationTargetException(parent, target, "team non-empty");

        assertThat(ex.parentAgentId()).isEqualTo(parent);
        assertThat(ex.targetMemberId()).isEqualTo(target);
    }

    @Test
    void is_a_business_exception() {
        AgentId parent = new AgentId(UUID.randomUUID());
        AgentId target = new AgentId(UUID.randomUUID());

        InvalidDelegationTargetException ex =
                new InvalidDelegationTargetException(parent, target, "parent vanished");

        assertThat(ex).isInstanceOf(BusinessException.class);
    }
}
