package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email address used as the user identifier (REQ-USR-002).
 *
 * <p>Performs a syntactic RFC 5322-style check (good-enough for v1) and enforces the 254-char
 * length cap from the {@code users.email} column. Construction throws
 * {@link ValidationException} with field {@code "email"} on any violation, so the REST
 * adapter can surface it as a per-field RFC 7807 error.
 *
 * <p><b>Case canonicalization.</b> The input is lowercased at construction
 * ({@link Locale#ROOT}) so that {@code "Alice@Example.Com"} and {@code "alice@example.com"}
 * compare equal and lookups never miss on casing. Whitespace is rejected by the regex.
 * Persistence side: a functional unique index on {@code lower(email)} (V004) guarantees the
 * same case-insensitive uniqueness at the database boundary.
 */
public record Email(String value) {

    private static final int MAX_LENGTH = 254;
    private static final Pattern PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new ValidationException("email", "must not be empty");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("email", "must be at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new ValidationException("email", "must be a valid email address");
        }
    }
}
