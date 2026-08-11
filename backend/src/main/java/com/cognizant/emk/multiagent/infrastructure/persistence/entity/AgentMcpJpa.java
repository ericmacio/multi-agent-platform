package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "agent_mcp_servers")
public class AgentMcpJpa {

    @EmbeddedId
    private Id id;

    protected AgentMcpJpa() {
    }

    public AgentMcpJpa(Id id) {
        this.id = id;
    }

    public AgentMcpJpa(UUID agentId, String mcpServerName) {
        this.id = new Id(agentId, mcpServerName);
    }

    public Id getId() { return id; }
    public UUID getAgentId() { return id.agentId(); }
    public String getMcpServerName() { return id.mcpServerName(); }

    @Embeddable
    public record Id(
            @Column(name = "agent_id", nullable = false) UUID agentId,
            @Column(name = "mcp_server_name", nullable = false, length = 64) String mcpServerName
    ) implements Serializable {
    }
}
