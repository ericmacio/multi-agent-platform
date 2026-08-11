package com.cognizant.emk.multiagent.infrastructure.web.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/users}.
 *
 * <p>Mirror of {@code LoginRequest}: {@code String} fields with bean-validation at the
 * binding stage, then the controller constructs the {@code Email}, {@code Password},
 * and {@code Role} domain types at the boundary so policy / format violations land as
 * per-field 400 {@code VALIDATION_ERROR} with the correct field name.
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 256) String password,
        @NotBlank String role) {
}
