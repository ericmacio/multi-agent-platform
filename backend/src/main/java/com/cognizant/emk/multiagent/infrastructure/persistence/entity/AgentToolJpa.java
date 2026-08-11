package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "agent_tools")
public class AgentToolJpa {

    @EmbeddedId
    private Id id;

    protected AgentToolJpa() {
    }

    public AgentToolJpa(Id id) {
        this.id = id;
    }

    public AgentToolJpa(UUID agentId, String toolName) {
        this.id = new Id(agentId, toolName);
    }

    public Id getId() { return id; }
    public UUID getAgentId() { return id.agentId(); }
    public String getToolName() { return id.toolName(); }

    @Embeddable
    public record Id(
            @Column(name = "agent_id", nullable = false) UUID agentId,
            @Column(name = "tool_name", nullable = false, length = 64) String toolName
    ) implements Serializable {
    }
}
