package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link UserJpa}.
 *
 * <p><b>Case-insensitive email lookups.</b> Callers SHOULD pass an already-canonicalized
 * (lowercase) value — every code path goes through the {@code Email} domain value object,
 * which lowercases at construction (Locale.ROOT). The {@code users.email} column is also
 * guaranteed to hold lowercase values by the V004 migration (backfill + functional unique
 * index on {@code lower(email)}), so the exact-match finder below is correct: there is no
 * casing variation to defend against at this layer.
 *
 * <p>The keyset-paged finders sort by {@code (createdAt DESC, id DESC)} so newest users
 * appear first; the second finder also enforces the strict
 * {@code (createdAt, id) <} keyset condition relative to the supplied cursor. Mirror of
 * the {@code ApiKeyJpaRepository} pattern from EPIC-04.
 */
public interface UserJpaRepository extends JpaRepository<UserJpa, UUID> {

    /**
     * Looks up a user by exact (lowercase) email match. The functional unique index
     * {@code ux_users_email_lower} guarantees at most one matching row.
     */
    Optional<UserJpa> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM UserJpa u ORDER BY u.createdAt DESC, u.id DESC")
    List<UserJpa> findFirstPage(Pageable pageable);

    @Query("SELECT u FROM UserJpa u "
            + "WHERE u.createdAt < :lastCreatedAt "
            + "   OR (u.createdAt = :lastCreatedAt AND u.id < :lastId) "
            + "ORDER BY u.createdAt DESC, u.id DESC")
    List<UserJpa> findPageAfter(
            @Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable);
}
