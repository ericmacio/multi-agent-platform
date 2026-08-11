package com.cognizant.emk.multiagent.infrastructure.web.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/login}.
 *
 * <p>{@code @NotBlank} catches empty or whitespace-only inputs at the binding stage and
 * surfaces as {@code MethodArgumentNotValidException} → 400 {@code VALIDATION_ERROR}. Stricter
 * format / policy checks are deferred to the {@link com.cognizant.emk.multiagent.domain.user.Email}
 * and {@link com.cognizant.emk.multiagent.domain.user.Password} value-object constructors so that
 * the same validation rules are enforced regardless of the call site.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 256) String password) {
}
