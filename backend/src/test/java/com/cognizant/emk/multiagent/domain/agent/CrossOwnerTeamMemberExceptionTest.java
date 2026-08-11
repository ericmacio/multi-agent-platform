package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CrossOwnerTeamMemberExceptionTest {

    @Test
    void message_contains_the_offending_member_id() {
        UUID id = UUID.fromString("99999999-8888-7777-6666-555555555555");
        CrossOwnerTeamMemberException ex = new CrossOwnerTeamMemberException(new AgentId(id));
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void is_a_conflict_exception() {
        CrossOwnerTeamMemberException ex =
                new CrossOwnerTeamMemberException(new AgentId(UUID.randomUUID()));
        assertThat(ex).isInstanceOf(ConflictException.class);
    }
}
