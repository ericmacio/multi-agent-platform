package com.cognizant.emk.multiagent.application.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateRateLimitConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-19T12:00:00Z");

    @Mock private RateLimitConfigRepository repository;
    @Mock private RateLimitConfigChangeListener listener;

    private Clock clock;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        serviceLogger = (Logger) LoggerFactory.getLogger(UpdateRateLimitConfigService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    void happy_path_saves_with_canonicalized_offsetDateTime_invokes_listener_and_returns_persisted() {
        UserId admin = new UserId(UUID.randomUUID());
        UpdateRateLimitConfigService service = new UpdateRateLimitConfigService(
                repository, clock, List.of(listener));

        RateLimitConfig persisted = new RateLimitConfig(
                30, 200, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), Optional.of(admin));
        when(repository.save(any(), eq(admin), eq(NOW))).thenReturn(persisted);

        RateLimitConfig result = service.update(new UpdateRateLimitConfigCommand(30, 200, admin));

        ArgumentCaptor<RateLimitConfig> savedArg = ArgumentCaptor.forClass(RateLimitConfig.class);
        verify(repository).save(savedArg.capture(), eq(admin), eq(NOW));
        RateLimitConfig requestedToSave = savedArg.getValue();
        assertThat(requestedToSave.perMinute()).isEqualTo(30);
        assertThat(requestedToSave.perHour()).isEqualTo(200);
        assertThat(requestedToSave.updatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(requestedToSave.updatedBy()).hasValue(admin);

        verify(listener).onRateLimitConfigChanged(persisted);
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void listener_exception_is_logged_at_warn_and_does_not_propagate() {
        UserId admin = new UserId(UUID.randomUUID());
        UpdateRateLimitConfigService service = new UpdateRateLimitConfigService(
                repository, clock, List.of(listener));

        RateLimitConfig persisted = new RateLimitConfig(
                5, 25, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), Optional.of(admin));
        when(repository.save(any(), eq(admin), eq(NOW))).thenReturn(persisted);
        doThrow(new RuntimeException("cache rebuild failed"))
                .when(listener).onRateLimitConfigChanged(persisted);

        RateLimitConfig result = service.update(new UpdateRateLimitConfigCommand(5, 25, admin));

        assertThat(result).isSameAs(persisted);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .contains(Level.WARN);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(msg -> assertThat(msg).contains("cache rebuild failed"));
    }

    @Test
    void no_listeners_registered_still_completes_successfully() {
        UserId admin = new UserId(UUID.randomUUID());
        UpdateRateLimitConfigService service = new UpdateRateLimitConfigService(
                repository, clock, List.of());

        RateLimitConfig persisted = new RateLimitConfig(
                15, 80, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), Optional.of(admin));
        when(repository.save(any(), eq(admin), eq(NOW))).thenReturn(persisted);

        RateLimitConfig result = service.update(new UpdateRateLimitConfigCommand(15, 80, admin));

        assertThat(result).isSameAs(persisted);
        verify(listener, never()).onRateLimitConfigChanged(any());
    }
}
