package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ConversationJpa;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ConversationJpa}.
 *
 * <p>The four list finders mirror the {@code AgentJpaRepository} keyset shape:
 * an unfiltered first-page query and a "page after cursor" variant per owner
 * type. The optional {@code agentId} filter on {@code GET /conversations}
 * (US-10-006) is implemented by passing it through every query and filtering
 * with {@code (:agentId is null or c.agent.id = :agentId)} so a single set of
 * queries covers both filtered and unfiltered calls.
 */
public interface ConversationJpaRepository extends JpaRepository<ConversationJpa, UUID> {

    // ----- USER-owned -----

    @Query("SELECT c FROM ConversationJpa c "
            + "WHERE c.ownerUser.id = :ownerId "
            + "  AND (:agentId IS NULL OR c.agent.id = :agentId) "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<ConversationJpa> findFirstPageByUserOwner(
            @Param("ownerId") UUID ownerId,
            @Param("agentId") UUID agentId,
            Pageable pageable);

    @Query("SELECT c FROM ConversationJpa c "
            + "WHERE c.ownerUser.id = :ownerId "
            + "  AND (:agentId IS NULL OR c.agent.id = :agentId) "
            + "  AND (c.createdAt < :lastCreatedAt "
            + "    OR (c.createdAt = :lastCreatedAt AND c.id < :lastId)) "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<ConversationJpa> findPageAfterByUserOwner(
            @Param("ownerId") UUID ownerId,
            @Param("agentId") UUID agentId,
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable);

    // ----- SYSTEM-owned -----

    @Query("SELECT c FROM ConversationJpa c "
            + "WHERE c.ownerApiKey.clientId = :clientId "
            + "  AND (:agentId IS NULL OR c.agent.id = :agentId) "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<ConversationJpa> findFirstPageByClientOwner(
            @Param("clientId") String clientId,
            @Param("agentId") UUID agentId,
            Pageable pageable);

    @Query("SELECT c FROM ConversationJpa c "
            + "WHERE c.ownerApiKey.clientId = :clientId "
            + "  AND (:agentId IS NULL OR c.agent.id = :agentId) "
            + "  AND (c.createdAt < :lastCreatedAt "
            + "    OR (c.createdAt = :lastCreatedAt AND c.id < :lastId)) "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<ConversationJpa> findPageAfterByClientOwner(
            @Param("clientId") String clientId,
            @Param("agentId") UUID agentId,
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable);
}
