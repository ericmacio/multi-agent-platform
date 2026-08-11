package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ApiKeyJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ConversationJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.MessageJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.mapper.ConversationMapper;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ApiKeyJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ConversationJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.MessageJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA-backed adapter for the {@link ConversationRepository}
 * domain port (US-10-003).
 *
 * <p>Owner-scoped reads dispatch on the sealed {@link ConversationOwner} type
 * to one of two query pairs in {@link ConversationJpaRepository} — keyset
 * pagination over the {@code (created_at desc, id desc)} composite, mirroring
 * {@link AgentRepositoryAdapter}.
 *
 * <p>Owner-scoped writes resolve exactly one parent reference (UserJpa via
 * {@link UserJpaRepository#getReferenceById} or ApiKeyJpa via
 * {@link ApiKeyJpaRepository#getReferenceById}) so neither write triggers an
 * extra SELECT just to satisfy the association.
 */
@Component
public class ConversationRepositoryAdapter implements ConversationRepository {

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 100;

    private final ConversationJpaRepository conversationJpaRepository;
    private final MessageJpaRepository messageJpaRepository;
    private final AgentJpaRepository agentJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final ApiKeyJpaRepository apiKeyJpaRepository;

    public ConversationRepositoryAdapter(
            ConversationJpaRepository conversationJpaRepository,
            MessageJpaRepository messageJpaRepository,
            AgentJpaRepository agentJpaRepository,
            UserJpaRepository userJpaRepository,
            ApiKeyJpaRepository apiKeyJpaRepository) {
        this.conversationJpaRepository = conversationJpaRepository;
        this.messageJpaRepository = messageJpaRepository;
        this.agentJpaRepository = agentJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.apiKeyJpaRepository = apiKeyJpaRepository;
    }

    // ----- Conversation -----

    @Override
    @Transactional
    public Conversation save(Conversation conversation) {
        Optional<ConversationJpa> existing =
                conversationJpaRepository.findById(conversation.id().value());
        ConversationJpa saved;
        if (existing.isPresent()) {
            ConversationJpa row = existing.get();
            ConversationMapper.updateMutableFields(row, conversation);
            saved = conversationJpaRepository.save(row);
        } else {
            AgentJpa agentRef =
                    agentJpaRepository.getReferenceById(conversation.agentId().value());
            UserId userOwnerId = ConversationMapper.resolveUserRef(conversation.owner());
            ClientId clientOwnerId = ConversationMapper.resolveApiKeyRef(conversation.owner());
            UserJpa ownerUserRef = userOwnerId == null
                    ? null
                    : userJpaRepository.getReferenceById(userOwnerId.value());
            ApiKeyJpa ownerApiKeyRef = clientOwnerId == null
                    ? null
                    : apiKeyJpaRepository.getReferenceById(clientOwnerId.value());
            saved = conversationJpaRepository.save(
                    ConversationMapper.toJpa(conversation, agentRef, ownerUserRef, ownerApiKeyRef));
        }
        return ConversationMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Conversation> findById(ConversationId id) {
        return conversationJpaRepository.findById(id.value())
                .map(ConversationMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Conversation> listByOwner(
            ConversationOwner owner,
            Optional<AgentId> agentFilter,
            Cursor cursor,
            int pageSize) {
        validatePageSize(pageSize);
        int limit = pageSize + 1;
        PageRequest probe = PageRequest.of(0, limit);
        UUID agentIdParam = agentFilter.map(a -> a.value()).orElse(null);

        List<ConversationJpa> rows;
        if (owner instanceof ConversationOwner.UserOwner u) {
            rows = (cursor == null)
                    ? conversationJpaRepository.findFirstPageByUserOwner(
                            u.userId().value(), agentIdParam, probe)
                    : conversationJpaRepository.findPageAfterByUserOwner(
                            u.userId().value(),
                            agentIdParam,
                            cursor.lastCreatedAt(),
                            UUID.fromString(cursor.lastId()),
                            probe);
        } else if (owner instanceof ConversationOwner.SystemOwner s) {
            rows = (cursor == null)
                    ? conversationJpaRepository.findFirstPageByClientOwner(
                            s.clientId().value(), agentIdParam, probe)
                    : conversationJpaRepository.findPageAfterByClientOwner(
                            s.clientId().value(),
                            agentIdParam,
                            cursor.lastCreatedAt(),
                            UUID.fromString(cursor.lastId()),
                            probe);
        } else {
            throw new IllegalStateException(
                    "Unhandled ConversationOwner subtype: " + owner.getClass().getName());
        }

        return assemblePage(rows, pageSize);
    }

    @Override
    @Transactional
    public void deleteById(ConversationId id) {
        conversationJpaRepository.deleteById(id.value());
    }

    // ----- Message -----

    @Override
    @Transactional
    public Message appendMessage(Message message) {
        ConversationJpa parentRef =
                conversationJpaRepository.getReferenceById(message.conversationId().value());
        MessageJpa saved = messageJpaRepository.save(
                ConversationMapper.toJpa(message, parentRef));
        return ConversationMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Message> listMessages(
            ConversationId conversationId,
            Cursor cursor,
            int pageSize) {
        validatePageSize(pageSize);
        int limit = pageSize + 1;
        PageRequest probe = PageRequest.of(0, limit);

        List<MessageJpa> rows = (cursor == null)
                ? messageJpaRepository.findFirstPageByConversation(conversationId.value(), probe)
                : messageJpaRepository.findPageAfterByConversation(
                        conversationId.value(),
                        cursor.lastCreatedAt(),
                        UUID.fromString(cursor.lastId()),
                        probe);

        boolean hasMore = rows.size() > pageSize;
        List<Message> items = rows.stream()
                .limit(pageSize)
                .map(ConversationMapper::toDomain)
                .toList();

        Cursor nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Message last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.id().value().toString());
        }
        return new Page<>(items, nextCursor, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findLastN(ConversationId conversationId, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        List<MessageJpa> rows = messageJpaRepository.findLastNByConversation(
                conversationId.value(), PageRequest.of(0, n));
        // The query returns DESC; reverse to chronological ASC for the caller.
        List<Message> reversed = new ArrayList<>(rows.size());
        for (MessageJpa jpa : rows) {
            reversed.add(0, ConversationMapper.toDomain(jpa));
        }
        return Collections.unmodifiableList(reversed);
    }

    // ----- helpers -----

    private static void validatePageSize(int pageSize) {
        if (pageSize < PAGE_SIZE_MIN || pageSize > PAGE_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + PAGE_SIZE_MIN + ", " + PAGE_SIZE_MAX + "]");
        }
    }

    private static Page<Conversation> assemblePage(List<ConversationJpa> rows, int pageSize) {
        boolean hasMore = rows.size() > pageSize;
        List<Conversation> items = rows.stream()
                .limit(pageSize)
                .map(ConversationMapper::toDomain)
                .toList();

        Cursor nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Conversation last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.id().value().toString());
        }
        return new Page<>(items, nextCursor, pageSize);
    }
}
