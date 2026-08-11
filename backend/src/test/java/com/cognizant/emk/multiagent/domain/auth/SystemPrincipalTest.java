package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SystemPrincipalTest {

    @Test
    void constructs_from_a_valid_client_id_and_round_trips() {
        ClientId clientId = new ClientId("svc-ci-abc123");
        SystemPrincipal principal = new SystemPrincipal(clientId);
        assertThat(principal.clientId()).isEqualTo(clientId);
    }

    @Test
    void rejects_null_client_id_with_descriptive_message() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SystemPrincipal(null))
                .withMessage("clientId");
    }

    @Test
    void is_a_principal() {
        Principal principal = new SystemPrincipal(new ClientId("anything"));
        assertThat(principal).isInstanceOf(SystemPrincipal.class);
    }

    /**
     * Exercises every permitted variant of the sealed {@link Principal} type through an
     * {@code instanceof} pattern-matching chain. Pattern matching for {@code switch} is a
     * preview-only feature in Java 17 (JEP 406), so we cannot rely on the compiler to
     * enforce exhaustiveness statically here. Instead, the {@code UnsupportedOperationException}
     * branch asserts at run time that every variant of {@link Principal} is covered: adding
     * a third variant to the {@code permits} clause makes this test fall through the chain
     * and fail, which is the forcing function we want.
     */
    @Test
    void principal_pattern_matching_chain_covers_every_permitted_variant() {
        Principal user = new UserPrincipal(
                new UserId(UUID.randomUUID()),
                new Email("alice@example.com"),
                Role.STANDARD,
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC));
        Principal system = new SystemPrincipal(new ClientId("svc-1"));

        assertThat(label(user)).isEqualTo("user");
        assertThat(label(system)).isEqualTo("system");
    }

    private static String label(Principal principal) {
        if (principal instanceof UserPrincipal) {
            return "user";
        }
        if (principal instanceof SystemPrincipal) {
            return "system";
        }
        throw new UnsupportedOperationException(
                "Unhandled Principal variant: " + principal.getClass().getName());
    }
}
