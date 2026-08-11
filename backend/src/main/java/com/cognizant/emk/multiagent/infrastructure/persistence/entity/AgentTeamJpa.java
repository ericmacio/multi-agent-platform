package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "agent_team")
public class AgentTeamJpa {

    @EmbeddedId
    private Id id;

    protected AgentTeamJpa() {
    }

    public AgentTeamJpa(Id id) {
        this.id = id;
    }

    public AgentTeamJpa(UUID parentAgentId, UUID memberAgentId) {
        this.id = new Id(parentAgentId, memberAgentId);
    }

    public Id getId() { return id; }
    public UUID getParentAgentId() { return id.parentAgentId(); }
    public UUID getMemberAgentId() { return id.memberAgentId(); }

    @Embeddable
    public record Id(
            @Column(name = "parent_agent_id", nullable = false) UUID parentAgentId,
            @Column(name = "member_agent_id", nullable = false) UUID memberAgentId
    ) implements Serializable {
    }
}
