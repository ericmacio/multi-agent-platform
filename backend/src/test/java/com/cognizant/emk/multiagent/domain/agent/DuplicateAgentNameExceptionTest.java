package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateAgentNameExceptionTest {

    @Test
    void message_contains_the_offending_name() {
        DuplicateAgentNameException ex = new DuplicateAgentNameException(new AgentName("alpha"));
        assertThat(ex.getMessage()).contains("alpha");
    }

    @Test
    void is_a_conflict_exception_so_the_subclass_handler_routes_to_DUPLICATE_AGENT_NAME() {
        DuplicateAgentNameException ex = new DuplicateAgentNameException(new AgentName("alpha"));
        // The US-06-003 subclass handler matches on the concrete type; the generic
        // ConflictException handler from US-05-003 routes to plain CONFLICT — the
        // instanceof relationship below is what makes both the dispatch and the
        // fallback contract work.
        assertThat(ex).isInstanceOf(ConflictException.class);
    }
}
