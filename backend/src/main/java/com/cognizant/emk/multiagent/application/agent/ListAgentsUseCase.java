package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Use case for {@code GET /agents} (REQ-AGT-006). Owner-scoped at the
 * repository layer — the service never sees rows belonging to another user.
 */
public interface ListAgentsUseCase {

    Page<Agent> list(ListAgentsQuery query);

    record ListAgentsQuery(UserId ownerId, Cursor cursor, PageSize pageSize) {

        public ListAgentsQuery {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(pageSize, "pageSize");
        }
    }
}
