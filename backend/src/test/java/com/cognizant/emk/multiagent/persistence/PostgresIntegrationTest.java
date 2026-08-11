package com.cognizant.emk.multiagent.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base class for persistence-needing integration tests.
 *
 * <p><b>Prerequisite (no Docker).</b> The project specs forbid Docker locally, so tests
 * connect to a real, locally-installed PostgreSQL. A one-time setup is required on the
 * developer's machine:
 * <pre>
 *   psql -U postgres -c "CREATE DATABASE multi_agent_test OWNER &lt;app-user&gt;;"
 * </pre>
 * Connection details default to {@code jdbc:postgresql://localhost:5432/multi_agent_test}
 * with user/password {@code postgres}/{@code postgres}; override via the env vars
 * {@code TEST_DB_URL}, {@code TEST_DB_USERNAME}, {@code TEST_DB_PASSWORD}.
 *
 * <p><b>Schema state.</b> {@link #cleanAndMigrate()} drops and re-applies every migration
 * before the first test of each class (via {@code @BeforeAll} +
 * {@code @TestInstance(PER_CLASS)}), so tests start from an empty, freshly-migrated schema.
 */
@SpringBootTest
@TestInstance(Lifecycle.PER_CLASS)
public abstract class PostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @BeforeAll
    void cleanAndMigrate() {
        flyway.clean();
        flyway.migrate();
    }
}
