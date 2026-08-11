package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNotFoundExceptionTest {

    @Test
    void message_contains_the_uuid() {
        UUID id = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef0123456789");
        AgentNotFoundException ex = new AgentNotFoundException(new AgentId(id));
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void is_a_not_found_exception_so_the_generic_handler_routes_it_to_404() {
        AgentNotFoundException ex = new AgentNotFoundException(new AgentId(UUID.randomUUID()));
        assertThat(ex).isInstanceOf(NotFoundException.class);
    }
}
