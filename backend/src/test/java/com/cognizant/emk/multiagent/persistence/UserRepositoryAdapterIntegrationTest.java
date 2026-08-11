package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the JPA adapter for {@link UserRepository}: it sees the seeded
 * admin row, persists fresh users, and round-trips a {@link User#withNewPasswordHash}
 * mutation through to the database.
 */
class UserRepositoryAdapterIntegrationTest extends PostgresIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";
    private static final String SEEDED_ADMIN_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void findByEmail_returns_the_seeded_admin_after_flyway() {
        Optional<User> admin = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL));
        assertThat(admin).isPresent();
        User u = admin.get();
        assertThat(u.email().value()).isEqualTo(SEEDED_ADMIN_EMAIL);
        assertThat(u.role()).isEqualTo(Role.ADMIN);
        assertThat(u.disabled()).isFalse();
        assertThat(u.mustChangePassword()).isTrue();
        assertThat(u.passwordHash()).isEqualTo(SEEDED_ADMIN_HASH);
        assertThat(u.id().value()).isNotNull();
    }

    @Test
    void save_then_findByEmail_and_findById_round_trip_a_new_user() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        User toSave = new User(
                new UserId(UUID.randomUUID()),
                new Email("alice@example.test"),
                "$2a$10$ZZZZZZZZZZZZZZZZZZZZZuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.STANDARD,
                false,
                false,
                now,
                now);

        User saved = userRepository.save(toSave);
        assertThat(saved.id()).isEqualTo(toSave.id());

        assertThat(userRepository.findById(toSave.id()))
                .isPresent()
                .get()
                .extracting(User::email)
                .isEqualTo(new Email("alice@example.test"));

        assertThat(userRepository.findByEmail(new Email("alice@example.test")))
                .isPresent()
                .get()
                .extracting(User::id)
                .isEqualTo(toSave.id());
    }

    /**
     * Inserts a fresh user with {@code mustChangePassword=true} and applies the domain
     * mutation through the adapter. Uses a dedicated row rather than mutating the seeded
     * admin so tests stay order-independent (a single {@code flyway.clean()} runs per
     * class, not per method).
     */
    @Test
    void save_after_withNewPasswordHash_writes_new_hash_and_clears_must_change_flag() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        Email email = new Email("rotate-target@example.test");
        User initial = new User(
                new UserId(UUID.randomUUID()),
                email,
                "$2a$10$BBBBBBBBBBBBBBBBBBBBBuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.STANDARD,
                false,
                true,
                now,
                now);
        User saved = userRepository.save(initial);

        OffsetDateTime later = now.plusMinutes(5);
        String newHash = "$2a$10$CCCCCCCCCCCCCCCCCCCCCuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
        userRepository.save(saved.withNewPasswordHash(newHash, later));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String hashInDb = jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, email.value());
        Boolean mustChange = jdbc.queryForObject(
                "select must_change_password from users where email = ?", Boolean.class, email.value());
        assertThat(hashInDb).isEqualTo(newHash);
        assertThat(mustChange).isFalse();

        User reloaded = userRepository.findByEmail(email).orElseThrow();
        assertThat(reloaded.passwordHash()).isEqualTo(newHash);
        assertThat(reloaded.mustChangePassword()).isFalse();
        assertThat(reloaded.id()).isEqualTo(saved.id());
    }
}
