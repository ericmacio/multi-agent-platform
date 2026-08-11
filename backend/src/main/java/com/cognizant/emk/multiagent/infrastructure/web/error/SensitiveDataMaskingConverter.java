package com.cognizant.emk.multiagent.infrastructure.web.error;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback converter that masks token-shaped substrings in log messages.
 *
 * <p>Defense-in-depth against accidental leaks of credentials in log output (REQ-SEC-004).
 * Three patterns are masked, in order:
 * <ol>
 *   <li>BCrypt password hashes (e.g. {@code $2a$10$...} 60 chars long).</li>
 *   <li>{@code Bearer <value>} headers (the prefix and value together).</li>
 *   <li>JWT-shaped strings: three Base64Url segments separated by dots, total length ≥ 40.</li>
 * </ol>
 *
 * <p>Registered as a Logback conversion word in {@code logback-spring.xml}; every appender
 * consumes it via the layout pattern so masked output is what reaches stdout / files / log
 * collectors. The {@link #mask(String)} static helper is unit-testable without a Logback event.
 */
public final class SensitiveDataMaskingConverter extends ClassicConverter {

    static final String MASK = "***";

    /** BCrypt: {@code $2a$10$<53 chars>}. */
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$.{53}");

    /** {@code Bearer <token>} (case-insensitive). The whole match — prefix and value — is masked. */
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_\\-.]+");

    /** Three Base64Url segments separated by dots. Length filtered post-match. */
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");

    private static final int JWT_MIN_LENGTH = 40;

    @Override
    public String convert(ILoggingEvent event) {
        return mask(event.getFormattedMessage());
    }

    /** Returns the input with every token-shaped substring replaced by {@value #MASK}. */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String masked = BCRYPT.matcher(input).replaceAll(MASK);
        masked = BEARER.matcher(masked).replaceAll(MASK);
        masked = maskJwt(masked);
        return masked;
    }

    private static String maskJwt(String input) {
        Matcher matcher = JWT.matcher(input);
        StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            String match = matcher.group();
            String replacement = match.length() >= JWT_MIN_LENGTH ? MASK : match;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
