package com.cognizant.emk.multiagent.infrastructure.web.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CorrelationIdFilter} — pure servlet-level coverage,
 * no Spring context. Exercises the four contract points:
 * <ol>
 *   <li>well-formed inbound header → echoed verbatim;</li>
 *   <li>malformed inbound → silently regenerated (no rejection);</li>
 *   <li>absent inbound → fresh UUID generated;</li>
 *   <li>{@link MDC} is cleared even when the downstream chain throws.</li>
 * </ol>
 */
class CorrelationIdFilterTest {

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void wellformed_inbound_header_is_echoed_and_set_on_mdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "my-trace-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("my-trace-001");
        assertThat(chain.mdcValueDuringChain).isEqualTo("my-trace-001");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void malformed_inbound_header_is_silently_regenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "value with space and bang!");
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        String emitted = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(emitted).isNotEqualTo("value with space and bang!");
        assertThat(UUID_PATTERN.matcher(emitted).matches())
                .as("malformed inbound should be replaced by a UUID v4")
                .isTrue();
        assertThat(chain.mdcValueDuringChain).isEqualTo(emitted);
    }

    @Test
    void absent_inbound_header_is_generated_as_uuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);

        String emitted = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(emitted).isNotNull();
        assertThat(UUID_PATTERN.matcher(emitted).matches()).isTrue();
        assertThat(chain.mdcValueDuringChain).isEqualTo(emitted);
    }

    @Test
    void mdc_is_cleared_even_when_chain_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain failing = (req, res) -> { throw new RuntimeException("downstream boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream boom");

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                .as("MDC must be cleared in a finally block — Tomcat reuses threads")
                .isNull();
    }

    @Test
    void empty_string_inbound_header_is_regenerated() throws Exception {
        // The regex requires 1+ chars; an empty string falls to the regen path.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new RecordingChain());

        String emitted = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(UUID_PATTERN.matcher(emitted).matches()).isTrue();
    }

    @Test
    void inbound_header_at_max_length_is_accepted() throws Exception {
        String maxLen = "a".repeat(128);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, maxLen);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new RecordingChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(maxLen);
    }

    @Test
    void inbound_header_over_max_length_is_regenerated() throws Exception {
        String tooLong = "a".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, tooLong);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new RecordingChain());

        String emitted = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(emitted).isNotEqualTo(tooLong);
        assertThat(UUID_PATTERN.matcher(emitted).matches()).isTrue();
    }

    /** Captures the MDC value at the moment the chain is invoked so the test can assert on it. */
    private static final class RecordingChain implements FilterChain {

        String mdcValueDuringChain;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            mdcValueDuringChain = MDC.get(CorrelationIdFilter.MDC_KEY);
        }
    }
}
