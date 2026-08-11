/**
 * Chat bounded context — application layer.
 *
 * <p>Carries:
 * <ul>
 *   <li>the provider-agnostic {@link com.cognizant.emk.multiagent.application.chat.LlmChatClient}
 *   port and its companion records ({@code ChatRequest}, {@code ChatChunk},
 *   {@code ChatResult}, {@code ChatMessage}, {@code Role},
 *   {@code SamplingParameters}) — US-09-001;</li>
 *   <li>the conversation lifecycle use cases — start (US-10-005), and the
 *   read / edit-title / delete / list-messages variants that follow it
 *   (US-10-006 .. US-10-010).</li>
 * </ul>
 *
 * <p>EPIC-11's {@code SendMessageService} and EPIC-12's
 * {@code DelegationService} land alongside these classes as their stories
 * come online.
 */
package com.cognizant.emk.multiagent.application.chat;
