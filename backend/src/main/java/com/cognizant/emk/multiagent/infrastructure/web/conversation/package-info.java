/**
 * REST adapter for {@code /conversations} (design §6.2.8). Carries the
 * {@code ConversationsController}, request / response DTOs, and the
 * static {@code ConversationResponseMapper}. Populated incrementally by
 * EPIC-10's per-endpoint stories (US-10-005 .. US-10-010); EPIC-11 adds
 * the SSE streaming send-message endpoint on top.
 */
package com.cognizant.emk.multiagent.infrastructure.web.conversation;
