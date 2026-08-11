package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "api_keys")
public class ApiKeyJpa {

    @Id
    @Column(name = "client_id", nullable = false, updatable = false, length = 64)
    private String clientId;

    @Column(name = "api_key_hash", nullable = false, length = 72)
    private String apiKeyHash;

    @Column(name = "label", length = 128)
    private String label;

    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ApiKeyJpa() {
    }

    public ApiKeyJpa(String clientId, String apiKeyHash, String label, boolean disabled,
                     OffsetDateTime createdAt) {
        this.clientId = clientId;
        this.apiKeyHash = apiKeyHash;
        this.label = label;
        this.disabled = disabled;
        this.createdAt = createdAt;
    }

    public String getClientId() { return clientId; }
    public String getApiKeyHash() { return apiKeyHash; }
    public String getLabel() { return label; }
    public boolean isDisabled() { return disabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public void setLabel(String label) { this.label = label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiKeyJpa other)) return false;
        return Objects.equals(clientId, other.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId);
    }
}
