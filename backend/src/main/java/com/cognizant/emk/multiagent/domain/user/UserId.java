package com.cognizant.emk.multiagent.domain.user;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed identifier for {@link User}. Wraps a non-null {@link UUID}. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId value must not be null");
    }

    public static UserId of(UUID uuid) {
        return new UserId(uuid);
    }
}
