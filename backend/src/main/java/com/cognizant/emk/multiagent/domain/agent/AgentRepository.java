package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Optional;

/**
 * Domain repository port for the {@link Agent} aggregate (design §4.1).
 *
 * <p>Pure Java — no Spring annotations. The adapter implementing this interface
 * lives under {@code infrastructure/persistence/adapter/} and bridges to Spring
 * Data JPA.
 *
 * <p>The interface covers every read/write the EPIC-06 use cases need:
 * <ul>
 *   <li>CRUD via {@link #findById}, {@link #save}, {@link #delete};</li>
 *   <li>owner-scoped paging via {@link #listByOwner};</li>
 *   <li>pre-flight checks for the three repository-backed invariants —
 *   duplicate-name ({@link #existsByOwnerAndName} /
 *   {@link #existsByOwnerAndNameExcludingId}), cross-owner team member
 *   ({@link #findOwnerOf}), nested team ({@link #hasNonEmptyTeam}).</li>
 * </ul>
 */
public interface AgentRepository {

    Optional<Agent> findById(AgentId id);

    /**
     * Returns one page of agents owned by {@code ownerId}, ordered newest-first
     * ({@code (createdAt, id) DESC}). Mirrors {@code ApiKeyRepository.listAll}
     * keyset shape.
     */
    Page<Agent> listByOwner(UserId ownerId, Cursor cursor, int pageSize);

    /**
     * Backs the duplicate-name pre-flight on {@code POST /agents} (US-06-004).
     * REQ-AGT-002 — name is unique per owner only, not globally.
     */
    boolean existsByOwnerAndName(UserId ownerId, AgentName name);

    /**
     * Same as {@link #existsByOwnerAndName} but ignores the agent identified by
     * {@code excluded}. Backs the duplicate-name pre-flight on
     * {@code PUT /agents/{id}} (US-06-007), where renaming an agent back to its
     * own current name must not collide with itself.
     */
    boolean existsByOwnerAndNameExcludingId(UserId ownerId, AgentName name, AgentId excluded);

    /**
     * Returns the owner of {@code id} without loading the full aggregate. Backs
     * the cross-owner team-member check (REQ-AGT-012). {@link Optional#empty()}
     * when the agent does not exist.
     */
    Optional<UserId> findOwnerOf(AgentId id);

    /**
     * Returns {@code true} if {@code id} has at least one team member. Backs the
     * single-level-team check (REQ-AGT-013).
     */
    boolean hasNonEmptyTeam(AgentId id);

    /**
     * Upserts the aggregate. The adapter is responsible for keeping the three
     * side tables ({@code agent_tools}, {@code agent_mcp_servers},
     * {@code agent_team}) consistent with the supplied aggregate atomically.
     */
    Agent save(Agent agent);

    /**
     * Hard-deletes the agent. Conversations and messages cascade via the V001
     * FK chain. A non-existent id is a silent no-op at this layer; the use case
     * is responsible for surfacing 404.
     */
    void delete(AgentId id);
}
