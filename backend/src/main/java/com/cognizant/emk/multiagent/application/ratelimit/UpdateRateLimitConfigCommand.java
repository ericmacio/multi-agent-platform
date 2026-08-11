package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Inputs to {@link UpdateRateLimitConfigUseCase#update(UpdateRateLimitConfigCommand)}.
 *
 * <p>The compact constructor mirrors the domain invariants (defense in depth on
 * top of the controller's Bean Validation {@code @Min(1)} and the domain record's
 * own checks). {@code admin} carries the calling admin's identifier so it can be
 * stamped as {@code updated_by} in the persisted row.
 */
public record UpdateRateLimitConfigCommand(int perMinute, int perHour, UserId admin) {

    public UpdateRateLimitConfigCommand {
        if (perMinute < 1) {
            throw new ValidationException("perMinute", "must be at least 1");
        }
        if (perHour < 1) {
            throw new ValidationException("perHour", "must be at least 1");
        }
        Objects.requireNonNull(admin, "admin");
    }
}
