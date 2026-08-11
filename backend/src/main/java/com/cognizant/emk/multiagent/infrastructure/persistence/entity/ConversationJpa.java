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

/**
 * JPA entity for the {@code conversations} table.
 *
 * <p>Post V005 (US-10-002), ownership is split across two mutually-exclusive
 * nullable associations: {@code ownerUser} ({@link UserJpa}) and
 * {@code ownerApiKey} ({@link ApiKeyJpa}). The PostgreSQL constraint
 * {@code ck_conversations_owner_xor} guarantees exactly one is non-null at
 * the schema level; the domain mapper additionally asserts the same on read
 * as defense-in-depth.
 *
 * <p>This entity is a dumb data carrier — it does NOT enforce the XOR in its
 * canonical constructor. The mapper sets exactly one of the two
 * {@code setOwnerUser} / {@code setOwnerApiKey} calls per write.
 */
@Entity
@Table(name = "conversations")
public class ConversationJpa {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    private AgentJpa agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private UserJpa ownerUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_client_id", referencedColumnName = "client_id")
    private ApiKeyJpa ownerApiKey;

    @Column(name = "title", length = 32)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ConversationJpa() {
    }

    public ConversationJpa(UUID id, AgentJpa agent, UserJpa ownerUser, ApiKeyJpa ownerApiKey,
                           String title, int messageCount,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.agent = agent;
        this.ownerUser = ownerUser;
        this.ownerApiKey = ownerApiKey;
        this.title = title;
        this.messageCount = messageCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public AgentJpa getAgent() { return agent; }
    public UserJpa getOwnerUser() { return ownerUser; }
    public ApiKeyJpa getOwnerApiKey() { return ownerApiKey; }
    public String getTitle() { return title; }
    public int getMessageCount() { return messageCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setOwnerUser(UserJpa ownerUser) { this.ownerUser = ownerUser; }
    public void setOwnerApiKey(ApiKeyJpa ownerApiKey) { this.ownerApiKey = ownerApiKey; }
    public void setTitle(String title) { this.title = title; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConversationJpa other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
