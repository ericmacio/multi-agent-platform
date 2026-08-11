package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered list of {@link AgentId} members the parent agent may delegate to
 * (REQ-AGT-011 / REQ-AGT-013).
 *
 * <p>Local invariants enforced here:
 * <ul>
 *   <li>No {@code null} entries;</li>
 *   <li>No duplicates (the canonical constructor preserves insertion order and
 *   rejects any repeat with {@link ValidationException} field {@code "team"});</li>
 *   <li>Defensive copy — the wrapped list is unmodifiable.</li>
 * </ul>
 *
 * <p>Non-local invariants ({@code REQ-AGT-012} same-owner, {@code REQ-AGT-013}
 * single-level, no self-reference on update) need repository access and live in
 * {@code CreateAgentService} / {@code UpdateAgentService}; they are intentionally
 * NOT checked here.
 */
public record Team(List<AgentId> members) {

    public static final Team EMPTY = new Team(List.of());

    public Team {
        Objects.requireNonNull(members, "members");
        List<AgentId> deduped = new ArrayList<>(members.size());
        Set<AgentId> seen = new HashSet<>();
        for (AgentId id : members) {
            if (id == null) {
                throw new ValidationException("team", "must not contain null members");
            }
            if (!seen.add(id)) {
                throw new ValidationException(
                        "team", "must not contain duplicate members: " + id.value());
            }
            deduped.add(id);
        }
        members = Collections.unmodifiableList(deduped);
    }
}
