import { z } from 'zod';
import type { components } from '@/generated/schema';

export type Conversation = components['schemas']['Conversation'];
export type Message = components['schemas']['Message'];
export type MessageRole = components['schemas']['MessageRole'];
export type UpdateConversationRequest = components['schemas']['UpdateConversationRequest'];

/**
 * Zod schema mirroring `openapi.yaml.UpdateConversationRequest` (SW-DESIGN §9.2).
 * Only the openapi-documented constraint (1..32 chars) is encoded; per-code
 * routing for 409 / 400 errors is owned by the consuming dialog.
 */
export const updateConversationSchema = z.object({
  title: z.string().min(1).max(32),
});

export type UpdateConversationValues = z.infer<typeof updateConversationSchema>;
