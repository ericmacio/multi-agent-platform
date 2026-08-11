package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ApiKeyJpa;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ApiKeyJpa}.
 *
 * <p>Carries the finders consumed by the {@code ApiKeyRepositoryAdapter} only — no
 * speculative methods. The keyset-paged finders sort by {@code (createdAt DESC,
 * clientId DESC)} so newest API keys appear first; the second finder also enforces the
 * strict {@code (createdAt, clientId) <} keyset condition relative to the supplied
 * cursor.
 *
 * <p>{@link #updateDisabledByClientId} is the {@code @Modifying} write path used by
 * US-04-008 to soft-revoke / re-enable a key without round-tripping the BCrypt hash
 * through the domain.
 */
public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyJpa, String> {

    @Query("SELECT a FROM ApiKeyJpa a ORDER BY a.createdAt DESC, a.clientId DESC")
    List<ApiKeyJpa> findFirstPage(Pageable pageable);

    @Query("SELECT a FROM ApiKeyJpa a "
            + "WHERE a.createdAt < :lastCreatedAt "
            + "   OR (a.createdAt = :lastCreatedAt AND a.clientId < :lastId) "
            + "ORDER BY a.createdAt DESC, a.clientId DESC")
    List<ApiKeyJpa> findPageAfter(
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") String lastId,
            Pageable pageable);

    @Modifying
    @Query("UPDATE ApiKeyJpa a SET a.disabled = :disabled WHERE a.clientId = :clientId")
    int updateDisabledByClientId(
            @Param("clientId") String clientId,
            @Param("disabled") boolean disabled);
}
