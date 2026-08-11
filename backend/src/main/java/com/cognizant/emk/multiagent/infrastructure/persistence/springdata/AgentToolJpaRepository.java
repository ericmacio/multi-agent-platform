package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentToolJpa;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AgentToolJpa}.
 *
 * <p>EPIC-06 adds the agent-scoped read + delete used by
 * {@code AgentRepositoryAdapter} when replacing the child rows wholesale on
 * every save.
 */
public interface AgentToolJpaRepository extends JpaRepository<AgentToolJpa, AgentToolJpa.Id> {

    @Query("SELECT t FROM AgentToolJpa t WHERE t.id.agentId = :agentId "
            + "ORDER BY t.id.toolName")
    List<AgentToolJpa> findByAgentId(@Param("agentId") UUID agentId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentToolJpa t WHERE t.id.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") UUID agentId);
}
