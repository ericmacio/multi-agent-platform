package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.regex.Pattern;

/**
 * Cleartext password value object enforcing the platform policy (REQ-SEC-001):
 * length ≥ 10, ≥ 1 uppercase letter, ≥ 1 special character.
 *
 * <p>The cleartext is exposed only via the explicit {@link #cleartext()} accessor.
 * {@link #toString()} is overridden to return {@code "Password{***}"} so that incidental
 * logging of a {@code Password} instance never leaks the value (REQ-SEC-002 / REQ-SEC-004).
 * Construction throws {@link ValidationException} with field {@code "password"} on any
 * policy violation.
 */
public record Password(String cleartext) {

    static final int MIN_LENGTH = 10;
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()\\-_=+\\[\\]{};:'\",.<>/?\\\\|~`]");

    public Password {
        if (cleartext == null || cleartext.length() < MIN_LENGTH) {
            throw new ValidationException("password", "must be at least " + MIN_LENGTH + " characters");
        }
        if (!UPPERCASE.matcher(cleartext).find()) {
            throw new ValidationException("password", "must contain at least one uppercase letter");
        }
        if (!SPECIAL.matcher(cleartext).find()) {
            throw new ValidationException("password", "must contain at least one special character");
        }
    }

    @Override
    public String toString() {
        return "Password{***}";
    }
}
