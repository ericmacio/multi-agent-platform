package com.cognizant.emk.multiagent.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the seed migrations leave the database in the expected initial state:
 * exactly one admin user (with {@code must_change_password=true}) and exactly one
 * rate-limit row with the documented defaults.
 *
 * <p>Overrides {@code spring.flyway.locations} to {@code classpath:db/migration} only,
 * dropping the EPIC-13 test-only V900 override so the assertion below sees the
 * production seed values (10, 50).
 */
class SeedMigrationsTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void productionMigrationsOnly(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void v002_seeds_a_single_admin_with_forced_password_change() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long adminCount = jdbc.queryForObject(
                "select count(*) from users where role = 'ADMIN'", Long.class);
        assertThat(adminCount).isEqualTo(1L);

        String email = jdbc.queryForObject(
                "select email from users where role = 'ADMIN'", String.class);
        assertThat(email).isEqualTo("bootstrap@example.test");

        Boolean mustChange = jdbc.queryForObject(
                "select must_change_password from users where role = 'ADMIN'", Boolean.class);
        assertThat(mustChange).isTrue();
    }

    @Test
    void v003_seeds_default_rate_limit_config() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long rowCount = jdbc.queryForObject(
                "select count(*) from rate_limit_config", Long.class);
        assertThat(rowCount).isEqualTo(1L);

        Integer perMinute = jdbc.queryForObject(
                "select per_minute from rate_limit_config where id = 1", Integer.class);
        Integer perHour = jdbc.queryForObject(
                "select per_hour from rate_limit_config where id = 1", Integer.class);
        assertThat(perMinute).isEqualTo(10);
        assertThat(perHour).isEqualTo(50);
    }
}
