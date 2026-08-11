package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.infrastructure.error.DatabaseAccessException;
import com.cognizant.emk.multiagent.infrastructure.persistence.adapter.RateLimitConfigRepositoryAdapter;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.RateLimitConfigJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit test for the {@code DataAccessException → DatabaseAccessException}
 * wrapping behavior introduced by US-14-002.
 *
 * <p>The Postgres integration test {@code RateLimitConfigRepositoryAdapterIntegrationTest}
 * covers the happy paths (load/save round-trip, missing-row IllegalStateException).
 * This sibling test mocks the Spring Data repository to force a
 * {@link org.springframework.dao.DataAccessException} on {@code findById} and
 * asserts the adapter re-wraps it as {@link DatabaseAccessException} so the
 * application layer never sees Spring-typed errors directly.
 *
 * <p>JPA entity constructors are protected (standard practice), so the save-path
 * variant of this assertion lives in the Postgres integration test rather than
 * here — the wrap mechanism is the same {@code JpaAccess.run(...)} helper, so
 * a single bracket exercise on {@code load()} suffices to lock the contract.
 */
class RateLimitConfigRepositoryAdapterWrapTest {

    private final RateLimitConfigJpaRepository jpaRepository = mock(RateLimitConfigJpaRepository.class);
    private final UserJpaRepository userJpaRepository = mock(UserJpaRepository.class);
    private final RateLimitConfigRepositoryAdapter adapter =
            new RateLimitConfigRepositoryAdapter(jpaRepository, userJpaRepository);

    @Test
    void wraps_DataAccessException_thrown_from_findById_on_load() {
        DataIntegrityViolationException cause = new DataIntegrityViolationException("boom");
        when(jpaRepository.findById(anyShort())).thenThrow(cause);

        assertThatThrownBy(adapter::load)
                .isInstanceOf(DatabaseAccessException.class)
                .hasMessageContaining("rate_limit_config.load failed")
                .hasCause(cause);
    }

    @Test
    void IllegalStateException_for_missing_seed_row_is_not_wrapped() {
        // The missing-seed sentinel is a startup invariant, not a DB failure;
        // it must surface as IllegalStateException, not DatabaseAccessException.
        when(jpaRepository.findById(anyShort())).thenReturn(Optional.empty());

        assertThatThrownBy(adapter::load)
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(DatabaseAccessException.class)
                .hasMessageContaining("V003");
    }
}
