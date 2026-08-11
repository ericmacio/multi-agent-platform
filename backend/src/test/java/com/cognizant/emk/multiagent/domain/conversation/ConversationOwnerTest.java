package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.auth.Principal;
import com.cognizant.emk.multiagent.domain.auth.SystemPrincipal;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ConversationOwnerTest {

    @Test
    void user_owner_rejects_null_user_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConversationOwner.UserOwner(null))
                .withMessage("userId");
    }

    @Test
    void system_owner_rejects_null_client_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConversationOwner.SystemOwner(null))
                .withMessage("clientId");
    }

    @Test
    void from_user_principal_yields_user_owner_carrying_the_same_user_id() {
        UserId userId = new UserId(UUID.randomUUID());
        UserPrincipal principal = new UserPrincipal(
                userId,
                new Email("alice@example.com"),
                Role.STANDARD,
                UUID.randomUUID(),
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));

        ConversationOwner owner = ConversationOwner.from(principal);

        assertThat(owner)
                .isInstanceOfSatisfying(ConversationOwner.UserOwner.class,
                        u -> assertThat(u.userId()).isEqualTo(userId));
    }

    @Test
    void from_system_principal_yields_system_owner_carrying_the_same_client_id() {
        ClientId clientId = new ClientId("svc-a");
        SystemPrincipal principal = new SystemPrincipal(clientId);

        ConversationOwner owner = ConversationOwner.from(principal);

        assertThat(owner)
                .isInstanceOfSatisfying(ConversationOwner.SystemOwner.class,
                        s -> assertThat(s.clientId()).isEqualTo(clientId));
    }

    @Test
    void from_rejects_null_principal() {
        assertThatNullPointerException()
                .isThrownBy(() -> ConversationOwner.from(null))
                .withMessage("principal");
    }

    @Test
    void sealed_hierarchy_pattern_matches_each_member() {
        // Project targets Java 17 — pattern-matching switch is preview-only.
        // We exercise the sealed hierarchy with instanceof pattern matching
        // (Java 16+), which is the same idiom production code uses.
        ConversationOwner user = new ConversationOwner.UserOwner(new UserId(UUID.randomUUID()));
        assertThat(describe(user)).startsWith("user:");

        ConversationOwner system = new ConversationOwner.SystemOwner(new ClientId("svc-a"));
        assertThat(describe(system)).isEqualTo("system:svc-a");
    }

    private static String describe(ConversationOwner owner) {
        if (owner instanceof ConversationOwner.UserOwner u) {
            return "user:" + u.userId().value();
        }
        if (owner instanceof ConversationOwner.SystemOwner s) {
            return "system:" + s.clientId().value();
        }
        throw new IllegalStateException("Unhandled ConversationOwner subtype: " + owner);
    }
}
