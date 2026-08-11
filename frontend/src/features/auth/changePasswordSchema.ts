import { z } from 'zod';
import { passwordPolicy } from './password';

/**
 * Mirrors `openapi.yaml.ChangePasswordRequest` plus a client-only
 * `confirmNewPassword` field. The cross-field refinement attaches the
 * mismatch error to the confirm field (so the user sees it next to the
 * field that needs to change).
 */
export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required'),
    newPassword: passwordPolicy,
    confirmNewPassword: z.string(),
  })
  .refine((d) => d.newPassword === d.confirmNewPassword, {
    path: ['confirmNewPassword'],
    message: 'Passwords do not match.',
  });

export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;
