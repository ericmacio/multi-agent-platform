package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link UpdateRateLimitConfigUseCase} implementation.
 *
 * <p>The {@link Clock} bean is injected (US-CR1-003 pattern: every time-aware
 * component reads from the Spring-managed clock, never {@code Clock.systemUTC()}
 * directly) so the {@code updatedAt} stamp can be virtualized from tests.
 *
 * <p>The listener is notified AFTER the {@code @Transactional} save commits.
 * Listener failures are logged at WARN and swallowed: the row is already
 * committed, and rolling it back because the cache failed to refresh would be
 * confusing for the admin. The cache will catch up on the next admin update.
 */
@Service
public class UpdateRateLimitConfigService implements UpdateRateLimitConfigUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateRateLimitConfigService.class);

    private final RateLimitConfigRepository repository;
    private final Clock clock;
    private final List<RateLimitConfigChangeListener> listeners;

    public UpdateRateLimitConfigService(
            RateLimitConfigRepository repository,
            Clock clock,
            List<RateLimitConfigChangeListener> listeners) {
        this.repository = repository;
        this.clock = clock;
        this.listeners = listeners;
    }

    @Override
    @Transactional
    public RateLimitConfig update(UpdateRateLimitConfigCommand command) {
        Instant now = clock.instant();
        RateLimitConfig requested = new RateLimitConfig(
                command.perMinute(),
                command.perHour(),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                Optional.of(command.admin()));
        RateLimitConfig saved = repository.save(requested, command.admin(), now);
        notifyListeners(saved);
        return saved;
    }

    private void notifyListeners(RateLimitConfig saved) {
        for (RateLimitConfigChangeListener listener : listeners) {
            try {
                listener.onRateLimitConfigChanged(saved);
            } catch (RuntimeException ex) {
                LOG.warn("Rate-limit config change listener {} failed: {}",
                        listener.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }
}
