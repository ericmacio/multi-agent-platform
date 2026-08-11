package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NestedTeamForbiddenExceptionTest {

    @Test
    void message_contains_the_offending_member_id() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        NestedTeamForbiddenException ex = new NestedTeamForbiddenException(new AgentId(id));
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void self_reference_factory_produces_a_normal_instance() {
        AgentId self = new AgentId(UUID.randomUUID());
        NestedTeamForbiddenException ex = NestedTeamForbiddenException.selfReference(self);
        assertThat(ex.getMessage()).contains(self.value().toString());
    }

    @Test
    void is_a_conflict_exception() {
        NestedTeamForbiddenException ex =
                new NestedTeamForbiddenException(new AgentId(UUID.randomUUID()));
        assertThat(ex).isInstanceOf(ConflictException.class);
    }
}
