package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentTeamJpa;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AgentTeamJpa}.
 *
 * <p>Carries the agent-scoped read + delete used by {@code AgentRepositoryAdapter}
 * on every save, plus {@link #existsByParentAgentId} which backs the
 * single-level-team rule (REQ-AGT-013) consumed by
 * {@code CreateAgentService} / {@code UpdateAgentService} (US-06-004 / US-06-007).
 */
public interface AgentTeamJpaRepository extends JpaRepository<AgentTeamJpa, AgentTeamJpa.Id> {

    @Query("SELECT t FROM AgentTeamJpa t WHERE t.id.parentAgentId = :parentAgentId "
            + "ORDER BY t.id.memberAgentId")
    List<AgentTeamJpa> findByParentAgentId(@Param("parentAgentId") UUID parentAgentId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentTeamJpa t WHERE t.id.parentAgentId = :parentAgentId")
    int deleteByParentAgentId(@Param("parentAgentId") UUID parentAgentId);

    @Query("SELECT (count(t) > 0) FROM AgentTeamJpa t "
            + "WHERE t.id.parentAgentId = :parentAgentId")
    boolean existsByParentAgentId(@Param("parentAgentId") UUID parentAgentId);
}
