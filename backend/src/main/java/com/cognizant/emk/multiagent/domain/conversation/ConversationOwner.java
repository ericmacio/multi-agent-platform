package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.auth.Principal;
import com.cognizant.emk.multiagent.domain.auth.SystemPrincipal;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Ownership of a {@link Conversation} — sealed sum type mirroring the
 * authentication-side {@link Principal} hierarchy (design §8.4 / §8.6).
 *
 * <p>Two members:
 * <ul>
 *   <li>{@link UserOwner} — a JWT-authenticated end user identified by
 *   {@link UserId}; persisted on the {@code conversations.owner_user_id}
 *   column (US-10-002 V005 schema split).</li>
 *   <li>{@link SystemOwner} — an API-key-authenticated machine principal
 *   identified by {@link ClientId} (REQ-AUTH-007); persisted on the
 *   {@code conversations.owner_client_id} column.</li>
 * </ul>
 *
 * <p>The sealed hierarchy lets adapter and use-case code switch
 * exhaustively without a {@code default} branch — if a future EPIC adds a
 * third permitted subtype, the compiler will fail every existing switch
 * until it is updated.
 */
public sealed interface ConversationOwner
        permits ConversationOwner.UserOwner, ConversationOwner.SystemOwner {

    /**
     * Builds the {@link ConversationOwner} that corresponds to the supplied
     * authenticated principal. Exhaustive over the sealed {@link Principal}
     * hierarchy by construction — adding a third {@code Principal} member
     * will trip the {@link IllegalStateException} fallback at runtime, which
     * is acceptable because the sealed type makes the omission visible to
     * any reader scanning for usages of {@code Principal}.
     *
     * <p>Implementation note: a pattern-matching {@code switch} would be
     * compile-time exhaustive but requires Java 21; the project targets
     * Java 17 so {@code instanceof} pattern matching plus a defensive
     * fallback is used instead.
     */
    static ConversationOwner from(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        if (principal instanceof UserPrincipal user) {
            return new UserOwner(user.id());
        }
        if (principal instanceof SystemPrincipal system) {
            return new SystemOwner(system.clientId());
        }
        throw new IllegalStateException(
                "Unhandled Principal subtype: " + principal.getClass().getName());
    }

    record UserOwner(UserId userId) implements ConversationOwner {
        public UserOwner {
            Objects.requireNonNull(userId, "userId");
        }
    }

    record SystemOwner(ClientId clientId) implements ConversationOwner {
        public SystemOwner {
            Objects.requireNonNull(clientId, "clientId");
        }
    }
}
