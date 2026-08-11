/**
 * Conversation bounded context — domain layer.
 *
 * <p>Carries the {@link com.cognizant.emk.multiagent.domain.conversation.Conversation}
 * and {@link com.cognizant.emk.multiagent.domain.conversation.Message} aggregates, the
 * value objects ({@link com.cognizant.emk.multiagent.domain.conversation.ConversationId},
 * {@link com.cognizant.emk.multiagent.domain.conversation.MessageId},
 * {@link com.cognizant.emk.multiagent.domain.conversation.MessageRole},
 * {@link com.cognizant.emk.multiagent.domain.conversation.MessageContent},
 * {@link com.cognizant.emk.multiagent.domain.conversation.Title},
 * {@link com.cognizant.emk.multiagent.domain.conversation.MessageCount}), the
 * {@link com.cognizant.emk.multiagent.domain.conversation.ConversationOwner} sealed
 * sum type (parallel to {@link com.cognizant.emk.multiagent.domain.auth.Principal}),
 * the {@link com.cognizant.emk.multiagent.domain.conversation.ConversationRepository}
 * port, and the per-context business exceptions
 * ({@link com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException},
 * {@link com.cognizant.emk.multiagent.domain.conversation.ConversationFullException}).
 * Populated by US-10-001.
 *
 * <p>The SSE send-message use case ({@code SendMessageService}), the title
 * auto-derivation orchestration, the memory-window assembly, and the JPA
 * adapter that bridges {@code ConversationRepository} to PostgreSQL land in
 * EPIC-11 / US-10-003 and consume this package's port and aggregates without
 * extending them.
 */
package com.cognizant.emk.multiagent.domain.conversation;
