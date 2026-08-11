package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorMapperTest {

    @Test
    void http_401_classifies_as_http_4xx() {
        Throwable t = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null);
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: http_4xx 401");
    }

    @Test
    void http_403_classifies_as_http_4xx() {
        Throwable t = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", null, null, null);
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: http_4xx 403");
    }

    @Test
    void http_429_classifies_as_http_429() {
        Throwable t = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: http_429");
    }

    @Test
    void http_500_classifies_as_http_5xx() {
        Throwable t = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null);
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: http_5xx 500");
    }

    @Test
    void http_503_classifies_as_http_5xx() {
        Throwable t = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null);
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: http_5xx 503");
    }

    @Test
    void socket_timeout_classifies_as_timeout() {
        Throwable t = new SocketTimeoutException("read timed out");
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: timeout");
    }

    @Test
    void connect_exception_classifies_as_connection_refused() {
        Throwable t = new ConnectException("Connection refused");
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: connection_refused");
    }

    @Test
    void ssl_handshake_classifies_as_connection_refused() {
        Throwable t = new SSLHandshakeException("handshake failed");
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: connection_refused");
    }

    @Test
    void resource_access_wrapping_socket_timeout_classifies_as_timeout() {
        Throwable t = new ResourceAccessException("io error", new SocketTimeoutException("read timed out"));
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: timeout");
    }

    @Test
    void resource_access_wrapping_connect_exception_classifies_as_connection_refused() {
        Throwable t = new ResourceAccessException("io error", new ConnectException("Connection refused"));
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: connection_refused");
    }

    @Test
    void unrecognised_runtime_exception_classifies_as_unknown() {
        Throwable t = new RuntimeException("boom");
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: unknown");
    }

    @Test
    void http_status_in_wrapping_runtime_exception_is_still_classified() {
        // The mapper walks the cause chain so a higher-level wrapper does not hide
        // the real provider status code from the operator-facing classification.
        Throwable inner = HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null);
        Throwable wrapper = new IllegalStateException("wrap", inner);
        assertThat(OpenAiErrorMapper.translate(wrapper))
                .isEqualTo("openai provider failure: http_5xx 502");
    }

    @Test
    void io_exception_without_specific_subclass_falls_through_to_unknown() {
        // Plain IOException is not in the recognised set; the mapper falls through.
        Throwable t = new IOException("write failed");
        assertThat(OpenAiErrorMapper.translate(t))
                .isEqualTo("openai provider failure: unknown");
    }
}
