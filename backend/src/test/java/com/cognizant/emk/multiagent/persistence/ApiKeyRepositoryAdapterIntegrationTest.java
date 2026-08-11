package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the JPA adapter for {@link ApiKeyRepository}: round-trips a fresh
 * key, walks the keyset-paged listing, and toggles the {@code disabled} flag through
 * the partial-update path.
 *
 * <p>Each test method clears the {@code api_keys} table first so cases stay
 * order-independent (the base class only re-runs Flyway once per class).
 */
class ApiKeyRepositoryAdapterIntegrationTest extends PostgresIntegrationTest {

    private static final String SAMPLE_HASH_A =
            "$2a$10$AAAAAAAAAAAAAAAAAAAAAuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
    private static final String SAMPLE_HASH_B =
            "$2a$10$BBBBBBBBBBBBBBBBBBBBBuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
    private static final String SAMPLE_HASH_C =
            "$2a$10$CCCCCCCCCCCCCCCCCCCCCuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void wipeApiKeys() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM api_keys");
    }

    @Test
    void save_then_find_by_client_id_round_trips_every_field() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ApiKey toSave = new ApiKey(
                new ClientId("svc-ci"),
                SAMPLE_HASH_A,
                "ci",
                false,
                now);

        ApiKey saved = apiKeyRepository.save(toSave);
        assertThat(saved).isEqualTo(toSave);

        Optional<ApiKey> loaded = apiKeyRepository.findByClientId(new ClientId("svc-ci"));
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(toSave);
    }

    @Test
    void find_by_client_id_for_unknown_client_returns_empty() {
        assertThat(apiKeyRepository.findByClientId(new ClientId("does-not-exist"))).isEmpty();
    }

    @Test
    void list_all_walks_three_rows_in_descending_created_at_order_across_two_pages() {
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // Insert oldest first so DB row order does not match the expected page order.
        apiKeyRepository.save(new ApiKey(new ClientId("svc-a"), SAMPLE_HASH_A, "a", false, t0));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-b"), SAMPLE_HASH_B, "b", false, t0.plusSeconds(1)));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-c"), SAMPLE_HASH_C, "c", false, t0.plusSeconds(2)));

        Page<ApiKey> page1 = apiKeyRepository.listAll(null, 2);
        assertThat(page1.items()).extracting(k -> k.clientId().value()).containsExactly("svc-c", "svc-b");
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.pageSize()).isEqualTo(2);

        Page<ApiKey> page2 = apiKeyRepository.listAll(page1.nextCursor(), 2);
        assertThat(page2.items()).extracting(k -> k.clientId().value()).containsExactly("svc-a");
        assertThat(page2.nextCursor()).isNull();
        assertThat(page2.pageSize()).isEqualTo(2);
    }

    @Test
    void list_all_on_empty_table_returns_empty_page_with_null_cursor() {
        Page<ApiKey> page = apiKeyRepository.listAll(null, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.pageSize()).isEqualTo(10);
    }

    @Test
    void list_all_resolves_clientId_tiebreak_when_created_at_is_identical() {
        OffsetDateTime same = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // Lexicographic order of client_id DESC: svc-c > svc-b > svc-a.
        apiKeyRepository.save(new ApiKey(new ClientId("svc-a"), SAMPLE_HASH_A, "a", false, same));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-b"), SAMPLE_HASH_B, "b", false, same));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-c"), SAMPLE_HASH_C, "c", false, same));

        Page<ApiKey> page = apiKeyRepository.listAll(null, 10);
        assertThat(page.items())
                .extracting(k -> k.clientId().value())
                .containsExactly("svc-c", "svc-b", "svc-a");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_all_with_cursor_excludes_the_cursor_row_itself() {
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        apiKeyRepository.save(new ApiKey(new ClientId("svc-a"), SAMPLE_HASH_A, "a", false, t0));
        apiKeyRepository.save(new ApiKey(new ClientId("svc-b"), SAMPLE_HASH_B, "b", false, t0.plusSeconds(1)));

        // Cursor positioned on the newest row → next page must start strictly older.
        Cursor cursor = new Cursor(t0.plusSeconds(1), "svc-b");
        Page<ApiKey> page = apiKeyRepository.listAll(cursor, 10);
        assertThat(page.items()).extracting(k -> k.clientId().value()).containsExactly("svc-a");
    }

    @Test
    void update_disabled_toggles_the_flag_in_the_database() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        apiKeyRepository.save(new ApiKey(new ClientId("svc-ci"), SAMPLE_HASH_A, "ci", false, now));

        apiKeyRepository.updateDisabled(new ClientId("svc-ci"), true);
        assertThat(jdbcDisabledFor("svc-ci")).isTrue();

        ApiKey reloaded = apiKeyRepository.findByClientId(new ClientId("svc-ci")).orElseThrow();
        assertThat(reloaded.disabled()).isTrue();

        apiKeyRepository.updateDisabled(new ClientId("svc-ci"), false);
        assertThat(jdbcDisabledFor("svc-ci")).isFalse();
    }

    @Test
    void update_disabled_for_unknown_client_id_is_a_silent_no_op() {
        // The domain-side "is this client known?" check lives in the use case (US-04-008);
        // the repository adapter itself only writes — the @Modifying UPDATE simply matches
        // zero rows.
        apiKeyRepository.updateDisabled(new ClientId("does-not-exist"), true);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM api_keys", Integer.class);
        assertThat(count).isEqualTo(0);
    }

    private boolean jdbcDisabledFor(String clientId) {
        Boolean disabled = jdbc.queryForObject(
                "SELECT disabled FROM api_keys WHERE client_id = ?",
                Boolean.class, clientId);
        return Boolean.TRUE.equals(disabled);
    }
}
