package com.cognizant.emk.multiagent.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's task-scheduling infrastructure so {@code @Scheduled} methods —
 * notably the {@code InMemoryJwtDenylistAdapter} sweep — fire on the configured cadence.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
