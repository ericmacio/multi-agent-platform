package com.cognizant.emk.multiagent.infrastructure.persistence.mapper;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import com.cognizant.emk.multiagent.domain.conversation.Title;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ApiKeyJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ConversationJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.MessageJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;

/**
 * Translates between the {@link Conversation} / {@link Message} domain
 * aggregates and the {@link ConversationJpa} / {@link MessageJpa} entities.
 *
 * <p>Owner translation is the load-bearing piece: the sealed
 * {@link ConversationOwner} maps to the two mutually-exclusive owner columns
 * via {@link #resolveUserRef} / {@link #resolveApiKeyRef}, and the read path
 * defensively asserts exactly one of the two is non-null (the
 * {@code ck_conversations_owner_xor} check constraint guarantees this at the
 * schema level — the assertion catches a regression where the constraint is
 * dropped or where the entity is constructed in-memory in a test without
 * going through the DB).
 */
public final class ConversationMapper {

    private ConversationMapper() {}

    // ----- Conversation -----

    public static Conversation toDomain(ConversationJpa jpa) {
        UserJpa ownerUser = jpa.getOwnerUser();
        ApiKeyJpa ownerApiKey = jpa.getOwnerApiKey();
        boolean userPresent = ownerUser != null;
        boolean clientPresent = ownerApiKey != null;
        if (userPresent == clientPresent) {
            throw new IllegalStateException(
                    "Inconsistent conversation row: " + jpa.getId()
                            + " has " + (userPresent ? "both" : "neither")
                            + " owner column populated");
        }
        ConversationOwner owner = userPresent
                ? new ConversationOwner.UserOwner(new UserId(ownerUser.getId()))
                : new ConversationOwner.SystemOwner(new ClientId(ownerApiKey.getClientId()));

        Title title = jpa.getTitle() == null ? null : new Title(jpa.getTitle());

        return new Conversation(
                new ConversationId(jpa.getId()),
                new AgentId(jpa.getAgent().getId()),
                owner,
                title,
                new MessageCount(jpa.getMessageCount()),
                jpa.getCreatedAt(),
                jpa.getUpdatedAt());
    }

    /**
     * Builds a fresh {@link ConversationJpa} for {@code save}. The caller is
     * expected to have already resolved the parent {@link AgentJpa} reference
     * (via {@code agentJpaRepository.getReferenceById}) and exactly one of the
     * {@link UserJpa} / {@link ApiKeyJpa} owner references depending on the
     * domain owner type.
     */
    public static ConversationJpa toJpa(
            Conversation domain,
            AgentJpa agentRef,
            UserJpa ownerUserRef,       // non-null iff domain.owner() is UserOwner
            ApiKeyJpa ownerApiKeyRef) { // non-null iff domain.owner() is SystemOwner
        return new ConversationJpa(
                domain.id().value(),
                agentRef,
                ownerUserRef,
                ownerApiKeyRef,
                domain.title() == null ? null : domain.title().value(),
                domain.messageCount().value(),
                domain.createdAt(),
                domain.updatedAt());
    }

    /**
     * Updates an existing {@link ConversationJpa} in-place with the mutable
     * fields of {@code domain}. Used by the {@code save} path when the row
     * already exists, so we re-use the entity's JPA-managed state (including
     * the owner column that does not change once persisted).
     */
    public static void updateMutableFields(ConversationJpa jpa, Conversation domain) {
        jpa.setTitle(domain.title() == null ? null : domain.title().value());
        jpa.setMessageCount(domain.messageCount().value());
        jpa.setUpdatedAt(domain.updatedAt());
    }

    // ----- Message -----

    public static Message toDomain(MessageJpa jpa) {
        return new Message(
                new MessageId(jpa.getId()),
                new ConversationId(jpa.getConversation().getId()),
                MessageRole.valueOf(jpa.getRole()),
                new MessageContent(jpa.getContent()),
                jpa.getCreatedAt());
    }

    public static MessageJpa toJpa(Message domain, ConversationJpa parentRef) {
        return new MessageJpa(
                domain.id().value(),
                parentRef,
                domain.role().name(),
                domain.content().value(),
                domain.createdAt());
    }

    // ----- helpers exposed for callers that need owner-type dispatch -----

    /**
     * Returns the {@link UserId} owner from the supplied {@link ConversationOwner}
     * iff it is a {@code UserOwner}; otherwise {@code null}. Used by the adapter
     * to know whether to load the {@link UserJpa} reference.
     */
    public static UserId resolveUserRef(ConversationOwner owner) {
        if (owner instanceof ConversationOwner.UserOwner u) {
            return u.userId();
        }
        return null;
    }

    /**
     * Returns the {@link ClientId} owner from the supplied {@link ConversationOwner}
     * iff it is a {@code SystemOwner}; otherwise {@code null}.
     */
    public static ClientId resolveApiKeyRef(ConversationOwner owner) {
        if (owner instanceof ConversationOwner.SystemOwner s) {
            return s.clientId();
        }
        return null;
    }
}
