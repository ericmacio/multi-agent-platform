package com.cognizant.emk.multiagent.infrastructure.web.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every incoming request with a single correlation identifier
 * (EPIC-15 / US-15-002 / REQ-OBS-001). Foundation for the JSON encoder
 * shipped by US-15-003.
 *
 * <p>Header contract:
 * <ul>
 *   <li>Inbound header {@value #HEADER_NAME} is accepted verbatim when it
 *   matches {@link #VALID_VALUE_PATTERN} (broad enough to admit external trace
 *   IDs — AWS X-Ray, OpenTelemetry hex segments, project-scoped strings).</li>
 *   <li>A null or malformed inbound value is silently replaced by a fresh
 *   {@link UUID#randomUUID()} — the filter does NOT reject the request, so a
 *   misconfigured upstream proxy cannot break the entire request path.</li>
 *   <li>The resolved value is written to {@link MDC} under
 *   {@value #MDC_KEY} BEFORE {@link FilterChain#doFilter} runs, so every log
 *   line emitted during the request carries it (consumed by the JSON encoder's
 *   {@code %mdc{correlationId:-}} field in {@code logback-spring.xml}).</li>
 *   <li>The same value is set as the {@value #HEADER_NAME} response header
 *   BEFORE the chain runs — so SSE responses, which commit headers eagerly,
 *   still carry it. The CORS configuration exposes the header to browsers via
 *   {@code Access-Control-Expose-Headers}.</li>
 *   <li>{@link MDC} is cleared in a {@code finally} block — Tomcat worker
 *   threads are reused, so a leaked entry would attribute the next request's
 *   logs to the wrong correlation ID.</li>
 * </ul>
 *
 * <p>Filter ordering: registered BEFORE {@code RateLimitFilter} in
 * {@code SpringSecurityConfig.securityFilterChain(...)} so the
 * {@code X-Correlation-Id} header is set on 429 responses too — a
 * bucket-exhausted request still carries the same observability signal as a
 * successful one.
 */
@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * Broad enough to admit AWS X-Ray IDs ({@code 1-5759e988-bd862e3fe1be46a994272793}),
     * OpenTelemetry lowercase hex segments, and project-scoped UUIDs. Rejects
     * whitespace, control characters, and obvious injection vectors (CRLF).
     */
    static final Pattern VALID_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String value = resolve(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, value);
        response.setHeader(HEADER_NAME, value);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String inbound) {
        if (inbound != null && VALID_VALUE_PATTERN.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
