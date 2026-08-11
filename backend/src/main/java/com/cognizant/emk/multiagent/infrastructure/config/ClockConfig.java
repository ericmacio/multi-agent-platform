package com.cognizant.emk.multiagent.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a single {@link Clock} bean so any time-aware adapter (notably
 * {@code InMemoryJwtDenylistAdapter}) can be tested with a fixed or offset clock without
 * monkey-patching {@code Instant.now()}.
 *
 * <p>Per the EPIC-03 design note, we intentionally use the JDK {@link Clock} type rather
 * than introducing an application port: it is a stable framework-free type, so the
 * application layer can depend on it without breaking the hexagonal layering rule.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
