package com.cognizant.emk.multiagent.application.shared;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Validated wrapper around the {@code pageSize} query parameter (design §6.1 / §10).
 *
 * <p>Bounds: {@code 1 <= value <= 100}. Default when the query param is omitted is
 * {@link #DEFAULT}. Out-of-range values surface as 400 {@code VALIDATION_ERROR} with
 * field {@code pageSize} via the {@link ValidationException} → {@code GlobalExceptionHandler}
 * path.
 */
public record PageSize(int value) {

    public static final int DEFAULT = 20;
    public static final int MAX = 100;
    public static final int MIN = 1;

    public PageSize {
        if (value < MIN || value > MAX) {
            throw new ValidationException(
                    "pageSize", "must be between " + MIN + " and " + MAX);
        }
    }

    /**
     * Constructs a {@link PageSize} from the optional {@code pageSize} query parameter.
     * Returns the default ({@value #DEFAULT}) when {@code requested} is {@code null};
     * otherwise validates and wraps.
     */
    public static PageSize fromQueryParam(Integer requested) {
        return requested == null ? new PageSize(DEFAULT) : new PageSize(requested);
    }
}
