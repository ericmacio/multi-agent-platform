package com.cognizant.emk.multiagent.infrastructure.web.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cognizant.emk.multiagent.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins REQ-OBS-002 — per-package log levels via Spring properties.
 *
 * <p>The test boots a Spring context with
 * {@code logging.level.com.cognizant.emk.multiagent.application.shared=DEBUG}
 * declared via {@link TestPropertySource}. Spring Boot's {@code LoggingSystem}
 * reconfigures Logback at context refresh; the test asserts that a
 * {@code log.debug(...)} call on a logger inside that package IS captured at
 * level DEBUG, proving the property-driven configuration is applied to the
 * Logback level mask — independent of the JSON encoder swap from US-15-003.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties =
        "logging.level.com.cognizant.emk.multiagent.application.shared=DEBUG")
class PerPackageLogLevelSmokeIntegrationTest extends PostgresIntegrationTest {

    private static final String LOGGER_NAME =
            "com.cognizant.emk.multiagent.application.shared.LogLevelProbe";

    private Logger probeLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        probeLogger = ctx.getLogger(LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.setContext(ctx);
        listAppender.start();
        probeLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        probeLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void debug_records_under_property_overridden_package_are_emitted() {
        // The TestPropertySource sets the package log level to DEBUG.
        // Spring Boot's LoggingSystem propagates that down to the Logback
        // logger; an attached ListAppender then captures the event.
        assertThat(probeLogger.isDebugEnabled())
                .as("logger under application.shared should be DEBUG-enabled via property")
                .isTrue();

        probeLogger.debug("debug-probe");

        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(listAppender.list.get(0).getFormattedMessage()).isEqualTo("debug-probe");
    }

    @Test
    void info_records_under_property_overridden_package_still_pass_through() {
        // DEBUG includes INFO — the override raises verbosity, it does not
        // narrow it to a single level.
        probeLogger.info("info-probe");
        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }
}
