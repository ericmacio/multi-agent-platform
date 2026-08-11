package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JWT-authenticated end-user principal.
 *
 * <p>Carries:
 * <ul>
 *   <li>{@link UserId}, {@link Email}, {@link Role} — the three identity claims the request
 *       layer needs for owner-scoping and authorization decisions;</li>
 *   <li>{@code jti} and {@code expiresAt} — the JWT identifier and natural expiry copied
 *       from the verified token so the {@code AuthController} can hand them to the logout
 *       use case (REQ-AUTH-011) without keeping a separate request-scoped holder.</li>
 * </ul>
 *
 * <p>Per US-03-010, the AC explicitly offered two options for surfacing {@code jti} /
 * {@code expiresAt} to the controller — a request-scoped holder, or stuffing them into the
 * principal — and asked to pick the simpler. Carrying them as fields here is the simpler:
 * one fewer bean, no scope juggling, and no extra plumbing in the {@code @AuthenticationPrincipal}
 * resolver. The fields are JDK types ({@link UUID}, {@link OffsetDateTime}) so the domain
 * layer remains free of framework / JJWT imports.
 */
public record UserPrincipal(
        UserId id,
        Email email,
        Role role,
        UUID jti,
        OffsetDateTime expiresAt) implements Principal {

    public UserPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
