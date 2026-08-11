/**
 * Infrastructure-layer exception hierarchy (design §9.1).
 *
 * <p>Two distinct families live here:
 * <ul>
 *   <li>{@code ExternalServiceException} (abstract) — concrete subclasses identify
 *       a failing external provider so the REST boundary can attach the right
 *       machine-readable {@code code} to the {@code ProblemDetails} body
 *       ({@code LLM_UNAVAILABLE}, {@code MCP_SERVER_ERROR}). All map to 502.</li>
 *   <li>{@code DatabaseAccessException} — wraps Spring's
 *       {@code org.springframework.dao.DataAccessException} at the persistence
 *       adapter boundary so the application layer never sees Spring types
 *       directly (US-14-002). Maps to 500 {@code INTERNAL_ERROR}.</li>
 * </ul>
 *
 * <p>{@code RateLimitedException} is also here for proximity to other
 * REST-bound exceptions, even though it is not infra-as-in-external-service.
 */
package com.cognizant.emk.multiagent.infrastructure.error;
