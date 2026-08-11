package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentMcpJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentTeamJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentToolJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.mapper.AgentMapper;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentMcpJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentTeamJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentToolJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA-backed adapter for the {@link AgentRepository} domain port.
 *
 * <p>The agent aggregate spans four tables (the parent {@code agents} row plus
 * three child tables — tools, MCP servers, team). On {@link #save} the adapter
 * replaces the child rows wholesale: a {@code deleteByAgentId} (or
 * {@code deleteByParentAgentId}) followed by a {@code saveAll} of the new rows
 * inside a single transaction. This is simpler than diffing and adequate at
 * the v1 64-user scale (REQ-NFR-005).
 *
 * <p>{@link #listByOwner} uses the same {@code pageSize + 1} fetch + trim
 * keyset strategy as {@code ApiKeyRepositoryAdapter}. The follow-up child-row
 * fetches happen per parent (small N at v1 scale); a future optimization could
 * batch them in a single query if list sizes ever become a concern.
 */
@Component
public class AgentRepositoryAdapter implements AgentRepository {

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 100;

    private final AgentJpaRepository agentJpaRepository;
    private final AgentToolJpaRepository agentToolJpaRepository;
    private final AgentMcpJpaRepository agentMcpJpaRepository;
    private final AgentTeamJpaRepository agentTeamJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public AgentRepositoryAdapter(
            AgentJpaRepository agentJpaRepository,
            AgentToolJpaRepository agentToolJpaRepository,
            AgentMcpJpaRepository agentMcpJpaRepository,
            AgentTeamJpaRepository agentTeamJpaRepository,
            UserJpaRepository userJpaRepository) {
        this.agentJpaRepository = agentJpaRepository;
        this.agentToolJpaRepository = agentToolJpaRepository;
        this.agentMcpJpaRepository = agentMcpJpaRepository;
        this.agentTeamJpaRepository = agentTeamJpaRepository;
        this.userJpaRepository = userJpaRepository;
    }

    // ------- reads -------

    @Override
    @Transactional(readOnly = true)
    public Optional<Agent> findById(AgentId id) {
        return agentJpaRepository.findById(id.value()).map(this::assembleDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Agent> listByOwner(UserId ownerId, Cursor cursor, int pageSize) {
        if (pageSize < PAGE_SIZE_MIN || pageSize > PAGE_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + PAGE_SIZE_MIN + ", " + PAGE_SIZE_MAX + "]");
        }
        int limit = pageSize + 1;
        PageRequest probe = PageRequest.of(0, limit);
        List<AgentJpa> rows = (cursor == null)
                ? agentJpaRepository.findFirstPageByOwner(ownerId.value(), probe)
                : agentJpaRepository.findPageAfterByOwner(
                        ownerId.value(),
                        cursor.lastCreatedAt(),
                        UUID.fromString(cursor.lastId()),
                        probe);

        boolean hasMore = rows.size() > pageSize;
        List<Agent> items = rows.stream()
                .limit(pageSize)
                .map(this::assembleDomain)
                .toList();

        Cursor nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Agent last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.id().value().toString());
        }
        return new Page<>(items, nextCursor, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByOwnerAndName(UserId ownerId, AgentName name) {
        return agentJpaRepository.existsByOwnerIdAndName(ownerId.value(), name.value());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByOwnerAndNameExcludingId(UserId ownerId, AgentName name, AgentId excluded) {
        return agentJpaRepository.existsByOwnerIdAndNameAndIdNot(
                ownerId.value(), name.value(), excluded.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserId> findOwnerOf(AgentId id) {
        return agentJpaRepository.findOwnerIdById(id.value()).map(UserId::new);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasNonEmptyTeam(AgentId id) {
        return agentTeamJpaRepository.existsByParentAgentId(id.value());
    }

    // ------- writes -------

    @Override
    @Transactional
    public Agent save(Agent agent) {
        UserJpa ownerRef = userJpaRepository.getReferenceById(agent.ownerId().value());
        AgentJpa savedParent = agentJpaRepository.save(AgentMapper.toAgentJpa(agent, ownerRef));

        // Wholesale replace the three child tables. The deletes use @Modifying with
        // flushAutomatically=true so the parent UPSERT is visible before the DELETEs
        // and the subsequent INSERTs do not collide on the composite primary keys.
        agentToolJpaRepository.deleteByAgentId(savedParent.getId());
        agentMcpJpaRepository.deleteByAgentId(savedParent.getId());
        agentTeamJpaRepository.deleteByParentAgentId(savedParent.getId());

        List<AgentToolJpa> tools = AgentMapper.toToolRows(agent);
        if (!tools.isEmpty()) {
            agentToolJpaRepository.saveAll(tools);
        }
        List<AgentMcpJpa> mcps = AgentMapper.toMcpRows(agent);
        if (!mcps.isEmpty()) {
            agentMcpJpaRepository.saveAll(mcps);
        }
        List<AgentTeamJpa> teamRows = AgentMapper.toTeamRows(agent);
        if (!teamRows.isEmpty()) {
            agentTeamJpaRepository.saveAll(teamRows);
        }

        return assembleDomain(savedParent);
    }

    @Override
    @Transactional
    public void delete(AgentId id) {
        agentJpaRepository.deleteById(id.value());
    }

    // ------- helpers -------

    private Agent assembleDomain(AgentJpa parent) {
        UUID parentId = parent.getId();
        List<AgentToolJpa> tools = agentToolJpaRepository.findByAgentId(parentId);
        List<AgentMcpJpa> mcps = agentMcpJpaRepository.findByAgentId(parentId);
        List<AgentTeamJpa> team = agentTeamJpaRepository.findByParentAgentId(parentId);
        return AgentMapper.toDomain(parent, tools, mcps, team);
    }
}
