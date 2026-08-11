package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.user.User;

/**
 * Translates the {@link User} domain aggregate to the {@link UserResponse} DTO.
 *
 * <p>The {@code passwordHash} field is intentionally <b>not</b> read by this mapper —
 * it never appears in the mapping expression, so there is no risk of a future refactor
 * accidentally including it in any user-facing response.
 */
public final class UserResponseMapper {

    private UserResponseMapper() {}

    public static UserResponse toResponse(User user) {
        return new UserResponse(
                user.id().value(),
                user.email().value(),
                user.role(),
                user.disabled(),
                user.mustChangePassword(),
                user.createdAt(),
                user.updatedAt());
    }
}
