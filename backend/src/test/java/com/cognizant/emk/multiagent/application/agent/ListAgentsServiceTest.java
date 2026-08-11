package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.agent.ListAgentsUseCase.ListAgentsQuery;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListAgentsServiceTest {

    @Mock private AgentRepository agentRepository;
    @InjectMocks private ListAgentsService service;

    @Test
    void forwards_a_null_cursor_for_the_first_page() {
        UserId owner = new UserId(UUID.randomUUID());
        Page<Agent> repoPage = new Page<>(List.<Agent>of(), null, 20);
        when(agentRepository.listByOwner(owner, null, 20)).thenReturn(repoPage);

        Page<Agent> result = service.list(
                new ListAgentsQuery(owner, null, PageSize.fromQueryParam(null)));
        assertThat(result).isSameAs(repoPage);
    }

    @Test
    void forwards_owner_cursor_and_page_size_verbatim() {
        UserId owner = new UserId(UUID.randomUUID());
        Cursor cursor = new Cursor(OffsetDateTime.now(ZoneOffset.UTC), "id-1");
        Page<Agent> repoPage = new Page<>(List.<Agent>of(), null, 50);
        when(agentRepository.listByOwner(owner, cursor, 50)).thenReturn(repoPage);

        Page<Agent> result = service.list(
                new ListAgentsQuery(owner, cursor, new PageSize(50)));
        assertThat(result).isSameAs(repoPage);
    }
}
