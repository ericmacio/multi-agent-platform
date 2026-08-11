package com.cognizant.emk.multiagent.domain.auth;

/**
 * The authenticated principal of an incoming request (design §8.4 / §8.6).
 *
 * <p>Sealed sum type with two members:
 * <ul>
 *   <li>{@link UserPrincipal} — a JWT-authenticated end user (delivered by EPIC-03).</li>
 *   <li>{@link SystemPrincipal} — an API-key-authenticated machine principal
 *   (delivered by EPIC-04 / US-04-001).</li>
 * </ul>
 *
 * <p>The sealed hierarchy is closed: any call site that switches on {@code Principal}
 * can do so exhaustively without a {@code default} branch, which is how the SYSTEM
 * authorization rules of design §8.6 stay statically checked.
 */
public sealed interface Principal permits UserPrincipal, SystemPrincipal {
}
