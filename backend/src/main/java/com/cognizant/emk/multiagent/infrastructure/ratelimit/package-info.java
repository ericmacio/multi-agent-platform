/**
 * Rate-limit infrastructure adapters. Hosts the Bucket4j-backed implementation of
 * {@link com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate} and its
 * companion {@link com.cognizant.emk.multiagent.application.ratelimit.RateLimitConfigChangeListener}
 * (US-13-004). The servlet filter that consumes the gate lives under
 * {@code infrastructure/web/ratelimit/} (US-13-005).
 *
 * <p>Bucket4j classes (the {@code io.github.bucket4j.*} package) MUST stay
 * inside this package; the application and domain layers depend only on the
 * gate port.
 */
package com.cognizant.emk.multiagent.infrastructure.ratelimit;
