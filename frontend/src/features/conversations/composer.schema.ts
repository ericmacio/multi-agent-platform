import { z } from 'zod';

/**
 * Zod schema mirroring `openapi.yaml.SendMessageRequest` (SW-DESIGN §9.2).
 * The 1024 character cap is enforced server-side; we mirror it client-side so
 * the user sees the limit before the round-trip fails.
 */
export const sendMessageSchema = z.object({
  content: z.string().min(1).max(1024),
});

export type SendMessageValues = z.infer<typeof sendMessageSchema>;
