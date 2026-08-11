package com.cognizant.emk.multiagent.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code V004__email_case_insensitive.sql} successfully applies and that the
 * resulting schema enforces case-insensitive uniqueness on {@code users.email} via a
 * functional unique index — and that the plain unique constraint from V001 is gone, so no
 * caller can accidentally rely on it.
 */
class EmailCaseInsensitiveMigrationTest extends PostgresIntegrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void flyway_history_records_v004_as_applied() {
        Boolean applied = new JdbcTemplate(dataSource).queryForObject(
                "select exists (select 1 from flyway_schema_history "
                        + "where version = '004' and success = true)",
                Boolean.class);
        assertThat(applied).isTrue();
    }

    @Test
    void functional_unique_index_on_lower_email_exists() {
        String indexDef = new JdbcTemplate(dataSource).queryForObject(
                "select indexdef from pg_indexes "
                        + "where schemaname = 'public' and indexname = 'ux_users_email_lower'",
                String.class);
        // PostgreSQL renders the expression as `lower((email)::text)`; assert on the
        // function and the column independently so the cast formatting does not matter.
        assertThat(indexDef)
                .as("functional unique index ux_users_email_lower must exist")
                .isNotNull()
                .contains("UNIQUE")
                .containsPattern("lower\\s*\\(")
                .contains("email")
                .contains("users");
    }

    @Test
    void plain_unique_constraint_on_users_email_is_gone() {
        Integer constraintCount = new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where table_schema = 'public' and table_name = 'users' "
                        + "and constraint_name = 'users_email_key' and constraint_type = 'UNIQUE'",
                Integer.class);
        assertThat(constraintCount)
                .as("V001's implicit unique constraint on users.email must be dropped by V004")
                .isZero();
    }
}
