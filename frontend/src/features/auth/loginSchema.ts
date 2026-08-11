import { z } from 'zod';

/**
 * Mirrors the openapi `LoginRequest` constraints: email format + length,
 * password non-empty + length. Exported separately from `LoginForm` so unit
 * tests can assert on the schema directly (SW-DESIGN §9.1).
 */
export const loginSchema = z.object({
  email: z
    .string()
    .min(1, 'Email is required')
    .email('Enter a valid email address')
    .max(254, 'Email is too long'),
  password: z.string().min(1, 'Password is required').max(256),
});

export type LoginValues = z.infer<typeof loginSchema>;
