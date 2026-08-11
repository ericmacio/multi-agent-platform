package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe controller used by {@link RateLimitFilterIntegrationTest} to
 * drive the global Bucket4j filter through a real Spring Security chain
 * without leaning on any feature endpoint. Mounted at {@code /_rl_probe};
 * the central {@code app.api.base-path} prefix makes the full path
 * {@code /api/v1/_rl_probe}.
 *
 * <p>Per project convention (US-CR1-002 / {@code backend/CLAUDE.md}), dev/probe
 * controllers MUST live in {@code src/test/java} only.
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} keeps the probe consistent
 * with the rest of the feature surface — the only way to reach it
 * unauthenticated is to walk through the filter chain, which is exactly what
 * the "unauthenticated traffic counts" scenario asserts (the bucket denies
 * before authentication runs).
 */
@RestController
public class RateLimitProbeController {

    @GetMapping("/_rl_probe")
    @PreAuthorize("isAuthenticated()")
    public String probe() {
        return "ok";
    }
}
