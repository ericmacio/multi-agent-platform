package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the end-to-end case-insensitivity contract delivered by US-CR1-001:
 * the domain {@link Email} canonicalizes to lowercase, persistence stores the
 * canonical form, lookups are case-insensitive, and the functional unique index
 * on {@code lower(email)} prevents a second row from sneaking in under a
 * different casing through direct JDBC.
 */
class UserRepositoryEmailCanonicalizationIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;

    @Test
    void saving_a_user_persists_email_in_lowercase() {
        User saved = userRepository.save(buildUser(new Email("Alice@Example.Com")));

        String stored = new JdbcTemplate(dataSource).queryForObject(
                "select email from users where id = ?", String.class, saved.id().value());
        assertThat(stored).isEqualTo("alice@example.com");
    }

    @Test
    void findByEmail_matches_regardless_of_casing() {
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(buildUserWithId(id, new Email("Charlie@Example.Com")));

        assertThat(userRepository.findByEmail(new Email("CHARLIE@EXAMPLE.COM")))
                .isPresent()
                .get()
                .extracting(User::id)
                .isEqualTo(id);
        assertThat(userRepository.findByEmail(new Email("charlie@example.com")))
                .isPresent();
        assertThat(userRepository.findByEmail(new Email("Charlie@Example.Com")))
                .isPresent();
    }

    @Test
    void inserting_a_duplicate_lowercase_email_violates_the_functional_unique_index() {
        userRepository.save(buildUser(new Email("dup@example.com")));

        // Force a raw JDBC insert that mimics what bypassing the value object would do
        // (different casing). The functional unique index MUST catch this.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (id, email, password_hash, role) values (?, ?, ?, 'STANDARD')",
                UUID.randomUUID(),
                "Dup@Example.Com",
                "$2a$10$" + "x".repeat(53)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static User buildUser(Email email) {
        return buildUserWithId(new UserId(UUID.randomUUID()), email);
    }

    private static User buildUserWithId(UserId id, Email email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new User(
                id,
                email,
                "$2a$10$ZZZZZZZZZZZZZZZZZZZZZuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.STANDARD,
                false,
                false,
                now,
                now);
    }
}
