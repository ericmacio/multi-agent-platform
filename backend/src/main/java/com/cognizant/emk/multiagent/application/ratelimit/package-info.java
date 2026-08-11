/**
 * Rate-limit bounded context — application layer.
 *
 * <p>Hosts the two admin-facing use cases consumed by the
 * {@code RateLimitAdminController} (US-13-006):
 * <ul>
 *   <li>{@link com.cognizant.emk.multiagent.application.ratelimit.GetRateLimitConfigUseCase}
 *       — read the live counters;</li>
 *   <li>{@link com.cognizant.emk.multiagent.application.ratelimit.UpdateRateLimitConfigUseCase}
 *       — replace the counters and notify the bucket adapter via
 *       {@link com.cognizant.emk.multiagent.application.ratelimit.RateLimitConfigChangeListener}.</li>
 * </ul>
 *
 * <p>The listener interface inverts the dependency on the
 * Bucket4j adapter (US-13-004): the bucket adapter implements the listener
 * port so the application layer never imports infrastructure types.
 * Populated by US-13-003.
 */
package com.cognizant.emk.multiagent.application.ratelimit;
