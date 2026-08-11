package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the EPIC-05 admin-side extensions of the {@link UserRepository}
 * JPA adapter: {@code existsByEmail}, the keyset-paged {@code listAll}, and
 * {@code delete}. The original EPIC-03 paths ({@code findByEmail}, {@code findById},
 * {@code save}) are covered by {@code UserRepositoryAdapterIntegrationTest}.
 */
class UserRepositoryAdapterAdminExtensionsIntegrationTest extends PostgresIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";
    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void wipeNonSeededUsers() {
        // Keep the seeded admin row (every test relies on it being present); remove any
        // user rows added by a previous test method so listAll assertions are
        // independent of test order.
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM users WHERE email <> ?", SEEDED_ADMIN_EMAIL);
    }

    // ------- existsByEmail -------

    @Test
    void exists_by_email_returns_true_for_the_seeded_admin() {
        assertThat(userRepository.existsByEmail(new Email(SEEDED_ADMIN_EMAIL))).isTrue();
    }

    @Test
    void exists_by_email_returns_false_for_an_unknown_address() {
        assertThat(userRepository.existsByEmail(new Email("nobody@example.test"))).isFalse();
    }

    @Test
    void exists_by_email_is_case_insensitive_via_the_email_value_object() {
        // Email lowercases at construction (US-CR1-001), so a mixed-case input still
        // matches the persisted lowercase row.
        assertThat(userRepository.existsByEmail(new Email("Bootstrap@Example.Test"))).isTrue();
    }

    // ------- listAll -------

    @Test
    void list_all_walks_three_inserted_rows_in_descending_created_at_order() {
        // The seeded admin row has an unknown createdAt (Flyway default), so anchor the
        // test on rows we insert with strictly later timestamps to keep ordering
        // predictable.
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(10)
                .truncatedTo(ChronoUnit.MILLIS);
        saveUser("a@example.test", base);
        saveUser("b@example.test", base.plusSeconds(1));
        saveUser("c@example.test", base.plusSeconds(2));

        Page<User> page1 = userRepository.listAll(null, 2);
        assertThat(page1.items()).extracting(u -> u.email().value())
                .containsExactly("c@example.test", "b@example.test");
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.pageSize()).isEqualTo(2);

        Page<User> page2 = userRepository.listAll(page1.nextCursor(), 2);
        assertThat(page2.items()).extracting(u -> u.email().value())
                .containsExactly("a@example.test", SEEDED_ADMIN_EMAIL);
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    void list_all_resolves_id_tiebreak_when_created_at_is_identical() {
        // Three rows with identical createdAt; UUIDs picked so their lexicographic
        // order in DESC is `id3 > id2 > id1`.
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
        OffsetDateTime same = OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(10)
                .truncatedTo(ChronoUnit.MILLIS);
        saveUser(id1, "tied-a@example.test", same);
        saveUser(id2, "tied-b@example.test", same);
        saveUser(id3, "tied-c@example.test", same);

        Page<User> page = userRepository.listAll(null, 3);
        assertThat(page.items()).extracting(u -> u.id().value())
                .containsExactly(id3, id2, id1);
    }

    @Test
    void list_all_with_cursor_excludes_the_cursor_row_itself() {
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC).plusYears(10).truncatedTo(ChronoUnit.MILLIS);
        UUID idA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID idB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        saveUser(idA, "older@example.test", t);
        saveUser(idB, "newer@example.test", t.plusSeconds(1));

        Cursor cursor = new Cursor(t.plusSeconds(1), idB.toString());
        Page<User> page = userRepository.listAll(cursor, 10);
        assertThat(page.items()).extracting(u -> u.email().value())
                .doesNotContain("newer@example.test");
        // "older@example.test" plus the seeded admin remain.
        assertThat(page.items()).extracting(u -> u.email().value())
                .contains("older@example.test", SEEDED_ADMIN_EMAIL);
    }

    @Test
    void list_all_only_returns_the_seeded_admin_when_no_extra_rows_exist() {
        Page<User> page = userRepository.listAll(null, 10);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).email().value()).isEqualTo(SEEDED_ADMIN_EMAIL);
        assertThat(page.nextCursor()).isNull();
    }

    // ------- delete -------

    @Test
    void delete_removes_the_row_so_find_by_id_returns_empty() {
        UserId saved = saveUser("to-delete@example.test",
                OffsetDateTime.now(ZoneOffset.UTC).plusYears(10).truncatedTo(ChronoUnit.MILLIS));

        userRepository.delete(saved);

        assertThat(userRepository.findById(saved)).isEmpty();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?", Integer.class, saved.value());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void delete_of_an_unknown_id_is_a_silent_no_op() {
        // Spring Data deleteById on a missing id is a no-op (no exception, no row write).
        // The use-case layer (US-05-008) is responsible for surfacing 404.
        userRepository.delete(new UserId(UUID.randomUUID()));
        // The seeded admin row is untouched.
        assertThat(userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL))).isPresent();
    }

    // ------- helpers -------

    private UserId saveUser(String email, OffsetDateTime createdAt) {
        return saveUser(UUID.randomUUID(), email, createdAt);
    }

    private UserId saveUser(UUID id, String email, OffsetDateTime createdAt) {
        User u = new User(
                new UserId(id),
                new Email(email),
                SAMPLE_HASH,
                Role.STANDARD,
                false,
                false,
                createdAt,
                createdAt);
        userRepository.save(u);
        return new UserId(id);
    }
}
