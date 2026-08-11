package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AgentJpa}.
 *
 * <p>EPIC-06 adds the finders consumed by {@code AgentRepositoryAdapter}:
 * duplicate-name pre-flight (REQ-AGT-002), ownership projection
 * (REQ-AGT-012 cross-owner check), and the keyset-paged owner-scoped listing.
 * Mirrors the {@code UserJpaRepository} / {@code ApiKeyJpaRepository} shapes
 * from earlier EPICs.
 *
 * <p>All finders go through explicit {@code @Query} to avoid any ambiguity
 * about the {@code owner.id} property-path resolution on the {@code @ManyToOne}
 * association.
 */
public interface AgentJpaRepository extends JpaRepository<AgentJpa, UUID> {

    @Query("SELECT (count(a) > 0) FROM AgentJpa a "
            + "WHERE a.owner.id = :ownerId AND a.name = :name")
    boolean existsByOwnerIdAndName(@Param("ownerId") UUID ownerId, @Param("name") String name);

    @Query("SELECT (count(a) > 0) FROM AgentJpa a "
            + "WHERE a.owner.id = :ownerId AND a.name = :name AND a.id <> :excludedId")
    boolean existsByOwnerIdAndNameAndIdNot(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("excludedId") UUID excludedId);

    @Query("SELECT a.owner.id FROM AgentJpa a WHERE a.id = :id")
    Optional<UUID> findOwnerIdById(@Param("id") UUID id);

    @Query("SELECT a FROM AgentJpa a WHERE a.owner.id = :ownerId "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    List<AgentJpa> findFirstPageByOwner(
            @Param("ownerId") UUID ownerId, Pageable pageable);

    @Query("SELECT a FROM AgentJpa a WHERE a.owner.id = :ownerId "
            + "  AND (a.createdAt < :lastCreatedAt "
            + "    OR (a.createdAt = :lastCreatedAt AND a.id < :lastId)) "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    List<AgentJpa> findPageAfterByOwner(
            @Param("ownerId") UUID ownerId,
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable);
}
