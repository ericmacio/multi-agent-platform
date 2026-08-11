package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "agents")
public class AgentJpa {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserJpa owner;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "description", nullable = false, length = 1024)
    private String description;

    @Column(name = "system_prompt", nullable = false, length = 1024)
    private String systemPrompt;

    @Column(name = "memory_size", nullable = false)
    private int memorySize;

    @Column(name = "llm_model", length = 64)
    private String llmModel;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "top_p")
    private Double topP;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AgentJpa() {
    }

    public AgentJpa(UUID id, UserJpa owner, String name, String description, String systemPrompt,
                    int memorySize, String llmModel, Double temperature, Integer maxOutputTokens,
                    Double topP, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.memorySize = memorySize;
        this.llmModel = llmModel;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.topP = topP;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UserJpa getOwner() { return owner; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSystemPrompt() { return systemPrompt; }
    public int getMemorySize() { return memorySize; }
    public String getLlmModel() { return llmModel; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public Double getTopP() { return topP; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public void setMemorySize(int memorySize) { this.memorySize = memorySize; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public void setTopP(Double topP) { this.topP = topP; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentJpa other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
