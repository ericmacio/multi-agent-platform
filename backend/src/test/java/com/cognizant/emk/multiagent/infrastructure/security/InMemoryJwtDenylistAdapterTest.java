package com.cognizant.emk.multiagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit test for {@link InMemoryJwtDenylistAdapter}. Uses the shared
 * {@link MutableClock} so expiry behaviour is exercised without sleeping.
 */
class InMemoryJwtDenylistAdapterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-05T12:00:00Z"));
    private final InMemoryJwtDenylistAdapter denylist = new InMemoryJwtDenylistAdapter(clock);

    @Test
    void add_then_contains_returns_true_while_not_expired() {
        UUID jti = UUID.randomUUID();
        denylist.add(jti, plus(Duration.ofMinutes(30)));
        assertThat(denylist.contains(jti)).isTrue();
        assertThat(denylist.size()).isEqualTo(1);
    }

    @Test
    void contains_returns_false_after_expiry_and_evicts_on_read() {
        UUID jti = UUID.randomUUID();
        denylist.add(jti, plus(Duration.ofMinutes(30)));

        clock.advance(Duration.ofMinutes(31));
        assertThat(denylist.contains(jti)).isFalse();
        // Read-time eviction: the entry should have been dropped.
        assertThat(denylist.size()).isZero();
    }

    @Test
    void add_for_an_already_past_expiresAt_is_a_noop() {
        UUID jti = UUID.randomUUID();
        denylist.add(jti, plus(Duration.ofMinutes(-5)));
        assertThat(denylist.size()).isZero();
        assertThat(denylist.contains(jti)).isFalse();
    }

    @Test
    void contains_returns_false_for_an_unknown_jti() {
        assertThat(denylist.contains(UUID.randomUUID())).isFalse();
    }

    @Test
    void sweep_drains_every_expired_entry() {
        UUID stillValid = UUID.randomUUID();
        UUID expired1 = UUID.randomUUID();
        UUID expired2 = UUID.randomUUID();

        denylist.add(stillValid, plus(Duration.ofMinutes(30)));
        denylist.add(expired1, plus(Duration.ofMinutes(5)));
        denylist.add(expired2, plus(Duration.ofMinutes(10)));
        assertThat(denylist.size()).isEqualTo(3);

        clock.advance(Duration.ofMinutes(11));
        denylist.sweep();

        assertThat(denylist.size()).isEqualTo(1);
        assertThat(denylist.contains(stillValid)).isTrue();
    }

    @Test
    void add_is_concurrency_safe() throws Exception {
        int n = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            UUID[] jtis = IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
            for (UUID jti : jtis) {
                pool.submit(() -> {
                    try {
                        start.await();
                        denylist.add(jti, plus(Duration.ofMinutes(30)));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(denylist.size()).isEqualTo(n);
    }

    private OffsetDateTime plus(Duration delta) {
        return OffsetDateTime.ofInstant(clock.instant().plus(delta), ZoneOffset.UTC);
    }
}
