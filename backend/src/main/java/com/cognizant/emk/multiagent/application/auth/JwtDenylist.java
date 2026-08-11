package com.cognizant.emk.multiagent.application.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Logout denylist for JWTs (REQ-AUTH-006 narrow exception, REQ-AUTH-011).
 *
 * <p>Holds a bounded set of {@code jti}s that the JWT filter rejects on every request.
 * Each entry self-expires no later than the corresponding token's natural {@code exp},
 * so the denylist remains O(active-logged-out tokens). Implementations are free to evict
 * lazily on read and/or on a scheduled sweep.
 */
public interface JwtDenylist {

    /**
     * Records that the JWT identified by {@code jti} is logged out until at least
     * {@code expiresAt}. Calls with an already-past {@code expiresAt} are a no-op
     * (no point keeping an entry the next read would drop anyway).
     */
    void add(UUID jti, OffsetDateTime expiresAt);

    /**
     * Returns {@code true} iff {@code jti} is on the denylist and its recorded expiry
     * is still in the future. Implementations SHOULD evict an expired entry observed
     * during this call (read-time eviction) so the denylist stays bounded between
     * scheduled sweeps.
     */
    boolean contains(UUID jti);

    /** Diagnostic accessor — current entry count. Not part of the request hot path. */
    int size();
}
