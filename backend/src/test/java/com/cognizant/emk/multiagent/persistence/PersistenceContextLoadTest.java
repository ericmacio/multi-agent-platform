package com.cognizant.emk.multiagent.persistence;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence smoke test: boots the full Spring context against a local PostgreSQL and
 * asserts that the DataSource, JPA, and Flyway wiring are present. No entities are
 * required — this guards the {@link PostgresIntegrationTest} support class itself.
 */
class PersistenceContextLoadTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Flyway flyway;

    @Test
    void datasource_jpa_and_flyway_are_wired() {
        assertThat(dataSource).isNotNull();
        assertThat(entityManager).isNotNull();
        assertThat(flyway).isNotNull();
    }
}
