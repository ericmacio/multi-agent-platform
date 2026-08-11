package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.MessageJpa;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link MessageJpa}.
 *
 * <p>Two read modes:
 * <ul>
 *   <li>{@code findFirstPageByConversation} / {@code findPageAfterByConversation}
 *   — chronological ASCENDING keyset paging, backs {@code GET
 *   /conversations/{id}/messages} (US-10-010).</li>
 *   <li>{@code findLastNByConversation} — DESCENDING order with {@code LIMIT
 *   n}, backs EPIC-11's memory-window assembly. The adapter reverses the
 *   list before returning so callers see chronological ASC.</li>
 * </ul>
 */
public interface MessageJpaRepository extends JpaRepository<MessageJpa, UUID> {

    @Query("SELECT m FROM MessageJpa m "
            + "WHERE m.conversation.id = :conversationId "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<MessageJpa> findFirstPageByConversation(
            @Param("conversationId") UUID conversationId,
            Pageable pageable);

    @Query("SELECT m FROM MessageJpa m "
            + "WHERE m.conversation.id = :conversationId "
            + "  AND (m.createdAt > :lastCreatedAt "
            + "    OR (m.createdAt = :lastCreatedAt AND m.id > :lastId)) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<MessageJpa> findPageAfterByConversation(
            @Param("conversationId") UUID conversationId,
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("SELECT m FROM MessageJpa m "
            + "WHERE m.conversation.id = :conversationId "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<MessageJpa> findLastNByConversation(
            @Param("conversationId") UUID conversationId,
            Pageable pageable);
}
