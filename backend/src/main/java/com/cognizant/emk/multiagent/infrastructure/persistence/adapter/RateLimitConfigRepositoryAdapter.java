package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.RateLimitConfigJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.RateLimitConfigJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA-backed adapter for {@link RateLimitConfigRepository}.
 *
 * <p>The {@code rate_limit_config} table is single-row by construction
 * ({@code id smallint primary key default 1 check (id = 1)}, seeded by V003).
 * This adapter never inserts — it loads the row, mutates the counters, and
 * saves. A missing row means Flyway V003 did not apply: surfaced as an
 * {@link IllegalStateException} so operators see it loudly rather than the
 * adapter silently re-creating a fresh row with hard-coded defaults.
 */
@Component
public class RateLimitConfigRepositoryAdapter implements RateLimitConfigRepository {

    private static final short ROW_ID = 1;

    private final RateLimitConfigJpaRepository rateLimitConfigJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public RateLimitConfigRepositoryAdapter(
            RateLimitConfigJpaRepository rateLimitConfigJpaRepository,
            UserJpaRepository userJpaRepository) {
        this.rateLimitConfigJpaRepository = rateLimitConfigJpaRepository;
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RateLimitConfig load() {
        // The IllegalStateException is a startup invariant (V003 did not apply),
        // not a DB failure — it is thrown OUTSIDE the JpaAccess wrapper so it
        // is not re-typed as a 500 DatabaseAccessException by the helper.
        RateLimitConfigJpa row = JpaAccess.run("rate_limit_config.load",
                        () -> rateLimitConfigJpaRepository.findById(ROW_ID))
                .orElseThrow(() -> new IllegalStateException(
                        "rate_limit_config row missing — Flyway seed V003 did not apply"));
        return toDomain(row);
    }

    @Override
    @Transactional
    public RateLimitConfig save(RateLimitConfig updated, UserId updatedBy, Instant now) {
        RateLimitConfigJpa row = JpaAccess.run("rate_limit_config.load",
                        () -> rateLimitConfigJpaRepository.findById(ROW_ID))
                .orElseThrow(() -> new IllegalStateException(
                        "rate_limit_config row missing — Flyway seed V003 did not apply"));
        return JpaAccess.run("rate_limit_config.save", () -> {
            UserJpa adminRef = userJpaRepository.getReferenceById(updatedBy.value());
            row.setPerMinute(updated.perMinute());
            row.setPerHour(updated.perHour());
            row.setUpdatedAt(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            row.setUpdatedBy(adminRef);
            return toDomain(rateLimitConfigJpaRepository.save(row));
        });
    }

    private static RateLimitConfig toDomain(RateLimitConfigJpa jpa) {
        Optional<UserId> updatedBy = Optional.ofNullable(jpa.getUpdatedBy())
                .map(UserJpa::getId)
                .map(UserId::new);
        return new RateLimitConfig(
                jpa.getPerMinute(),
                jpa.getPerHour(),
                jpa.getUpdatedAt(),
                updatedBy);
    }
}
