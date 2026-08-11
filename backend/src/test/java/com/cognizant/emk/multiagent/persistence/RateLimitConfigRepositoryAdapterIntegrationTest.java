package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres-backed contract test for {@link RateLimitConfigRepository} via the JPA
 * adapter. Exercises the V003 seed read, the load/save round-trip with the
 * {@code updated_by} FK to {@code users(id)}, and the "row missing" failure path.
 *
 * <p>Overrides {@code spring.flyway.locations} to {@code classpath:db/migration}
 * only, so the EPIC-13 test-only V900 override is NOT applied here and
 * {@link #loads_seeded_row()} can assert the production seed values (10, 50).
 */
class RateLimitConfigRepositoryAdapterIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void productionMigrationsOnly(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private RateLimitConfigRepository repository;

    @Autowired
    private DataSource dataSource;

    @Test
    void loads_seeded_row() {
        RateLimitConfig cfg = repository.load();

        assertThat(cfg.perMinute()).isEqualTo(10);
        assertThat(cfg.perHour()).isEqualTo(50);
        assertThat(cfg.updatedBy()).isEmpty();
        assertThat(cfg.updatedAt()).isNotNull();
    }

    @Test
    void saves_and_reloads_with_updated_by_set_to_bootstrap_admin() {
        UserId bootstrapAdminId = bootstrapAdminId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        RateLimitConfig updated = new RateLimitConfig(
                20, 100, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), Optional.empty());

        RateLimitConfig saved = repository.save(updated, bootstrapAdminId, now);
        assertThat(saved.perMinute()).isEqualTo(20);
        assertThat(saved.perHour()).isEqualTo(100);
        assertThat(saved.updatedBy()).hasValue(bootstrapAdminId);

        RateLimitConfig reloaded = repository.load();
        assertThat(reloaded.perMinute()).isEqualTo(20);
        assertThat(reloaded.perHour()).isEqualTo(100);
        assertThat(reloaded.updatedBy()).hasValue(bootstrapAdminId);
        assertThat(reloaded.updatedAt()).isEqualTo(saved.updatedAt());
    }

    @Test
    void throws_when_row_missing() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from rate_limit_config where id = 1");

        assertThatThrownBy(() -> repository.load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate_limit_config")
                .hasMessageContaining("V003");

        // Restore the seed so a subsequent test in this class still sees it. Although
        // PostgresIntegrationTest runs clean+migrate at @BeforeAll only, keeping the
        // assertion local to this method is hygiene.
        jdbc.update("insert into rate_limit_config (id, per_minute, per_hour) values (1, 10, 50)");
    }

    private UserId bootstrapAdminId() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID id = jdbc.queryForObject(
                "select id from users where role = 'ADMIN' limit 1", UUID.class);
        return new UserId(id);
    }
}
