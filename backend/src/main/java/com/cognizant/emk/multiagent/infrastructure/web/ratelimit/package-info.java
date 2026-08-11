/**
 * Servlet filter and admin REST controller for global rate limiting (EPIC-13).
 *
 * <ul>
 *   <li>{@link com.cognizant.emk.multiagent.infrastructure.web.ratelimit.RateLimitFilter}
 *       (US-13-005) — outermost filter in the Spring Security chain.</li>
 *   <li>{@link com.cognizant.emk.multiagent.infrastructure.web.ratelimit.RateLimitAdminController}
 *       (US-13-006) — admin endpoints under {@code /admin/rate-limit}.</li>
 * </ul>
 *
 * <p>The Bucket4j adapter implementing
 * {@link com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate} lives under
 * {@code infrastructure/ratelimit/} (US-13-004), separate from the web layer so that
 * Bucket4j classes do not creep into the servlet package.
 */
package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;
