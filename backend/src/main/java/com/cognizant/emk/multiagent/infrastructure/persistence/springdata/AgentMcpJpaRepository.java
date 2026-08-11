package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentMcpJpa;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AgentMcpJpa}.
 *
 * <p>EPIC-06 adds the agent-scoped read + delete used by
 * {@code AgentRepositoryAdapter} when replacing the child rows wholesale on
 * every save.
 */
public interface AgentMcpJpaRepository extends JpaRepository<AgentMcpJpa, AgentMcpJpa.Id> {

    @Query("SELECT m FROM AgentMcpJpa m WHERE m.id.agentId = :agentId "
            + "ORDER BY m.id.mcpServerName")
    List<AgentMcpJpa> findByAgentId(@Param("agentId") UUID agentId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentMcpJpa m WHERE m.id.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") UUID agentId);
}
