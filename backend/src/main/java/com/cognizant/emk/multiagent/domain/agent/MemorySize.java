package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Per-agent memory window size in number of persisted messages (REQ-AGT-004).
 *
 * <p>Bounds: {@code 1 <= value <= 36}. The REST adapter falls back to
 * {@link #DEFAULT} ({@code 12}) when the request omits the field. Out-of-range
 * values throw {@link ValidationException} with field {@code "memorySize"}.
 */
public record MemorySize(int value) {

    public static final int MIN = 1;
    public static final int MAX = 36;
    public static final MemorySize DEFAULT = new MemorySize(12);

    public MemorySize {
        if (value < MIN || value > MAX) {
            throw new ValidationException(
                    "memorySize", "must be between " + MIN + " and " + MAX);
        }
    }
}
