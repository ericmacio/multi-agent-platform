import { z } from 'zod';
import type { components } from '@/generated/schema';

export type ApiKey = components['schemas']['ApiKey'];
export type ApiKeyCreated = components['schemas']['ApiKeyCreated'];
export type CreateApiKeyRequest = components['schemas']['CreateApiKeyRequest'];
export type UpdateApiKeyRequest = components['schemas']['UpdateApiKeyRequest'];

/**
 * Zod schema mirroring `openapi.yaml.CreateApiKeyRequest`. Label is optional
 * and ≤128 chars — the spec does not impose a `min(1)` so the schema does
 * not either. There is intentionally no `updateApiKeySchema` —
 * `UpdateApiKeyRequest` only exposes a boolean `disabled` field toggled
 * directly via `useUpdateApiKey`.
 */
export const createApiKeySchema = z.object({
  label: z.string().max(128).optional(),
});

export type CreateApiKeyValues = z.infer<typeof createApiKeySchema>;
