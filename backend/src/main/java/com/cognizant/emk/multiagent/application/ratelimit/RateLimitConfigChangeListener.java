package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;

/**
 * Application-layer port notified after a successful admin update of the live
 * rate-limit configuration commits. The Bucket4j adapter (US-13-004) implements
 * this so it can rebuild its in-JVM bucket atomically without making the
 * application layer depend on the infrastructure adapter.
 *
 * <p>Implementations MUST be non-blocking — the bucket rebuild runs on the
 * calling thread.
 */
public interface RateLimitConfigChangeListener {

    void onRateLimitConfigChanged(RateLimitConfig updated);
}
