import { z } from 'zod';
import type { components } from '@/generated/schema';

export type AgentRequest = components['schemas']['AgentRequest'];
export type Agent = components['schemas']['Agent'];

/**
 * Zod schema mirroring `openapi.yaml.AgentRequest`'s client-visible constraints
 * (SW-DESIGN §9.2). The cross-field "single-level team" rule is intentionally
 * NOT mirrored — the server returns `NESTED_TEAM_FORBIDDEN` /
 * `CROSS_OWNER_TEAM_MEMBER`, which `AgentForm` (US-05-004) surfaces as
 * form-level errors. The `TeamPicker` exposes the rule as a live preview, but
 * the validation contract lives on the backend.
 */
export const agentSchema = z.object({
  name: z.string().min(1).max(32),
  description: z.string().min(1).max(1024),
  systemPrompt: z.string().min(1).max(1024),
  memorySize: z.number().int().min(1).max(36).default(12),
  llmModel: z.string().max(64).nullable().optional(),
  temperature: z.number().nullable().optional(),
  maxOutputTokens: z.number().int().min(1).nullable().optional(),
  topP: z.number().nullable().optional(),
  tools: z.array(z.string().max(64)).default([]),
  enabledMcpServers: z.array(z.string().max(64)).default([]),
  team: z.array(z.string().uuid()).default([]),
});

export type AgentValues = z.infer<typeof agentSchema>;
