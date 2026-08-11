import { z } from 'zod';
import type { components } from '@/generated/schema';
import { passwordPolicy } from '@/features/auth/password';

export type User = components['schemas']['User'];
export type CreateUserRequest = components['schemas']['CreateUserRequest'];
export type UpdateUserRequest = components['schemas']['UpdateUserRequest'];
export type Role = components['schemas']['Role'];

/**
 * Zod schema mirroring `openapi.yaml.CreateUserRequest`. Reuses the shared
 * `passwordPolicy` (SW-DESIGN §9.3) so the checklist copy stays identical
 * between admin-authored account creation and self-serve password change.
 *
 * There is intentionally no `updateUserSchema` — `UpdateUserRequest` only
 * exposes a boolean `disabled` field, toggled directly via `useUpdateUser`.
 */
export const createUserSchema = z.object({
  email: z.string().email().max(254),
  password: passwordPolicy,
  role: z.enum(['ADMIN', 'STANDARD']),
});

export type CreateUserValues = z.infer<typeof createUserSchema>;
