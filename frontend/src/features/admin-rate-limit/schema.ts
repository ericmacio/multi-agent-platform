import { z } from 'zod';
import type { components } from '@/generated/schema';

export type RateLimitConfig = components['schemas']['RateLimitConfig'];
export type RateLimitConfigRequest = components['schemas']['RateLimitConfigRequest'];

/**
 * Zod schema mirroring `openapi.yaml.RateLimitConfigRequest`. Both fields are
 * required integers ≥ 1. No upper bound is set (the spec does not impose one).
 * The response type `RateLimitConfig` adds read-only `updatedAt` / `updatedBy`
 * — those are NEVER sent back on update, so this schema is intentionally
 * request-only.
 */
export const rateLimitConfigSchema = z.object({
  perMinute: z.number().int().min(1),
  perHour: z.number().int().min(1),
});

export type RateLimitConfigValues = z.infer<typeof rateLimitConfigSchema>;
