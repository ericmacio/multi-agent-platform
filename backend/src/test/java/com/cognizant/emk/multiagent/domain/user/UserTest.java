package com.cognizant.emk.multiagent.domain.user;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class UserTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED = OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    private static User newSeededAdmin() {
        return new User(
                new UserId(UUID.randomUUID()),
                new Email("admin@example.com"),
                "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.ADMIN,
                false,
                true,
                CREATED,
                UPDATED);
    }

    @Test
    void is_active_returns_true_when_not_disabled() {
        assertThat(newSeededAdmin().isActive()).isTrue();
    }

    @Test
    void is_active_returns_false_when_disabled() {
        User u = newSeededAdmin();
        User disabled = new User(u.id(), u.email(), u.passwordHash(), u.role(),
                true, u.mustChangePassword(), u.createdAt(), u.updatedAt());
        assertThat(disabled.isActive()).isFalse();
    }

    @Test
    void with_new_password_hash_clears_must_change_password_and_bumps_updated_at() {
        User original = newSeededAdmin();
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 5, 12, 0, 0, 0, ZoneOffset.UTC);
        String newHash = "$2a$10$DIFFERENTabcdefghijklmnoaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        User updated = original.withNewPasswordHash(newHash, now);

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.email()).isEqualTo(original.email());
        assertThat(updated.role()).isEqualTo(original.role());
        assertThat(updated.disabled()).isEqualTo(original.disabled());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());

        assertThat(updated.passwordHash()).isEqualTo(newHash);
        assertThat(updated.mustChangePassword()).isFalse();
        assertThat(updated.updatedAt()).isEqualTo(now);
    }

    @Test
    void with_new_password_hash_rejects_null_arguments() {
        User user = newSeededAdmin();
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 5, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThatNullPointerException().isThrownBy(() -> user.withNewPasswordHash(null, now));
        assertThatNullPointerException().isThrownBy(() -> user.withNewPasswordHash("hash", null));
    }

    @Test
    void with_disabled_flips_the_flag_bumps_updated_at_and_preserves_every_other_field() {
        User original = newSeededAdmin();
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 5, 12, 0, 0, 0, ZoneOffset.UTC);

        User toggled = original.withDisabled(true, now);

        assertThat(toggled.disabled()).isTrue();
        assertThat(toggled.updatedAt()).isEqualTo(now);
        assertThat(toggled.id()).isEqualTo(original.id());
        assertThat(toggled.email()).isEqualTo(original.email());
        assertThat(toggled.role()).isEqualTo(original.role());
        assertThat(toggled.passwordHash()).isEqualTo(original.passwordHash());
        // mustChangePassword is preserved — withDisabled is orthogonal to the
        // forced-password-change flag.
        assertThat(toggled.mustChangePassword()).isEqualTo(original.mustChangePassword());
        assertThat(toggled.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void with_disabled_round_trip_false_true_false_returns_an_equivalent_aggregate() {
        User enabled = newSeededAdmin();
        OffsetDateTime t1 = OffsetDateTime.of(2026, 5, 5, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t2 = OffsetDateTime.of(2026, 5, 5, 13, 0, 0, 0, ZoneOffset.UTC);

        User disabled = enabled.withDisabled(true, t1);
        User reEnabled = disabled.withDisabled(false, t2);

        assertThat(reEnabled.disabled()).isFalse();
        assertThat(reEnabled.id()).isEqualTo(enabled.id());
        assertThat(reEnabled.email()).isEqualTo(enabled.email());
        assertThat(reEnabled.role()).isEqualTo(enabled.role());
        assertThat(reEnabled.passwordHash()).isEqualTo(enabled.passwordHash());
        assertThat(reEnabled.mustChangePassword()).isEqualTo(enabled.mustChangePassword());
    }

    @Test
    void with_disabled_rejects_null_now() {
        User user = newSeededAdmin();
        assertThatNullPointerException()
                .isThrownBy(() -> user.withDisabled(true, null))
                .withMessage("now");
    }

    @Test
    void constructor_rejects_null_required_fields() {
        OffsetDateTime t = CREATED;
        UserId id = new UserId(UUID.randomUUID());
        Email email = new Email("a@b.c");
        assertThatNullPointerException().isThrownBy(() ->
                new User(null, email, "h", Role.ADMIN, false, false, t, t));
        assertThatNullPointerException().isThrownBy(() ->
                new User(id, null, "h", Role.ADMIN, false, false, t, t));
        assertThatNullPointerException().isThrownBy(() ->
                new User(id, email, null, Role.ADMIN, false, false, t, t));
        assertThatNullPointerException().isThrownBy(() ->
                new User(id, email, "h", null, false, false, t, t));
        assertThatNullPointerException().isThrownBy(() ->
                new User(id, email, "h", Role.ADMIN, false, false, null, t));
        assertThatNullPointerException().isThrownBy(() ->
                new User(id, email, "h", Role.ADMIN, false, false, t, null));
    }
}
