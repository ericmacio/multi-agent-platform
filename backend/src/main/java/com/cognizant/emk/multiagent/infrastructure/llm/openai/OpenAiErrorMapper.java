package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies any throwable from Spring AI's {@code ChatModel} into a short,
 * payload-free string suitable for an {@code LlmUnavailableException} message
 * (REQ-LLM-005, REQ-SEC-004).
 *
 * <p>Shared between {@code OpenAiChatClientAdapter.call(...)} (US-09-004) and the
 * {@code stream(...)} {@code .onErrorMap(...)} (US-09-005) so the synchronous and
 * reactive paths surface the exact same classification.
 */
final class OpenAiErrorMapper {

    static final String PREFIX = "openai provider failure: ";

    private OpenAiErrorMapper() {}

    static String translate(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof HttpStatusCodeException sc) {
                return PREFIX + classifyHttpStatus(sc.getStatusCode().value());
            }
            if (cur instanceof SocketTimeoutException) {
                return PREFIX + "timeout";
            }
            if (cur instanceof ConnectException) {
                return PREFIX + "connection_refused";
            }
            if (cur instanceof SSLHandshakeException) {
                return PREFIX + "connection_refused";
            }
            if (cur instanceof ResourceAccessException) {
                // Spring's RestClient wraps low-level IO failures in ResourceAccessException;
                // the underlying cause (SocketTimeoutException, ConnectException, …) is
                // discovered on the next loop iteration. If we get here with no recognised
                // cause, treat the IO failure as a connection problem.
                if (cur.getCause() == null) {
                    return PREFIX + "connection_refused";
                }
            }
        }
        return PREFIX + "unknown";
    }

    private static String classifyHttpStatus(int code) {
        if (code == 429) {
            return "http_429";
        }
        if (code >= 400 && code < 500) {
            return "http_4xx " + code;
        }
        if (code >= 500 && code < 600) {
            return "http_5xx " + code;
        }
        return "http_" + code;
    }
}
