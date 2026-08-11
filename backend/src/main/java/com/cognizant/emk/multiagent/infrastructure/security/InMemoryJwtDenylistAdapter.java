package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.JwtDenylist;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process implementation of {@link JwtDenylist} (design §8.3).
 *
 * <p>Single-node v1 (TBD-1 covers a Redis-backed multi-node variant). Backed by a
 * {@link ConcurrentHashMap} so reads and writes from the JWT filter are lock-free.
 *
 * <p>Eviction strategy is two-pronged:
 * <ul>
 *   <li><b>Read-time</b>: {@link #contains(UUID)} drops an expired entry it observes,
 *   keeping the map bounded between sweeps under steady load.</li>
 *   <li><b>Scheduled</b>: {@link #sweep()} runs every 60 s as a backstop, draining
 *   entries whose owning request never came back to discover them.</li>
 * </ul>
 *
 * <p>Sensitive-data discipline: the {@code jti} appears in log output only at DEBUG
 * level (REQ-SEC-004).
 */
@Component
public class InMemoryJwtDenylistAdapter implements JwtDenylist {

    private static final Logger log = LoggerFactory.getLogger(InMemoryJwtDenylistAdapter.class);

    private final ConcurrentMap<UUID, OffsetDateTime> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryJwtDenylistAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void add(UUID jti, OffsetDateTime expiresAt) {
        if (expiresAt.isBefore(now())) {
            return; // already-expired entries would be evicted on the next read anyway.
        }
        entries.put(jti, expiresAt);
        log.debug("denylisted jti {} until {}", jti, expiresAt);
    }

    @Override
    public boolean contains(UUID jti) {
        OffsetDateTime expiresAt = entries.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(now())) {
            entries.remove(jti, expiresAt); // best-effort, only if still the same value
            return false;
        }
        return true;
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * Drops every entry whose recorded expiry is in the past. Runs every 60 seconds in a
     * Spring-managed scheduler thread (REQ-AUTH-011: the denylist remains bounded by the
     * configured JWT lifetime).
     */
    @Scheduled(fixedDelay = 60_000L)
    public void sweep() {
        OffsetDateTime now = now();
        int removed = 0;
        for (var entry : entries.entrySet()) {
            if (entry.getValue().isBefore(now) && entries.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("denylist sweep removed {} expired entries", removed);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
