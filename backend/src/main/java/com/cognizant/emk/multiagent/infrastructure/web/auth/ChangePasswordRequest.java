package com.cognizant.emk.multiagent.infrastructure.web.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /auth/password}.
 *
 * <p>{@code @NotBlank} catches empty values at the binding stage. The platform password
 * policy (length, uppercase, special) is enforced when the controller constructs
 * {@link com.cognizant.emk.multiagent.domain.user.Password} value objects out of these
 * fields — and that path attaches the per-field error name so the response mentions
 * {@code currentPassword} or {@code newPassword} explicitly.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(max = 256) String newPassword) {
}
