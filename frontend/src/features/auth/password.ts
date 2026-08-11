import { z } from 'zod';

/**
 * Platform password policy mirrored from
 * `openapi.yaml.ChangePasswordRequest.newPassword.description`:
 * "≥10 characters, ≥1 uppercase letter, ≥1 special character".
 *
 * Used at submit time via `ChangePasswordForm`'s Zod resolver. The companion
 * `evaluatePasswordPolicy` helper exposes each rule's pass/fail state
 * independently so the live policy checklist can update on every keystroke.
 */
export const passwordPolicy = z
  .string()
  .min(10, 'At least 10 characters')
  .max(256)
  .regex(/[A-Z]/, 'At least one uppercase letter')
  .regex(/[^A-Za-z0-9]/, 'At least one special character');

export type PasswordRuleKey = 'length' | 'uppercase' | 'special';

export type PasswordRule = {
  key: PasswordRuleKey;
  label: string;
  valid: boolean;
};

const LENGTH_LABEL = 'At least 10 characters';
const UPPERCASE_LABEL = 'At least one uppercase letter';
const SPECIAL_LABEL = 'At least one special character';

/**
 * Return the three-rule live state in a **fixed order** (`length`, `uppercase`,
 * `special`) so the rendered list does not reorder as the user types. Each
 * rule is checked independently — unlike `passwordPolicy.safeParse`, which
 * short-circuits on the first failure.
 */
export function evaluatePasswordPolicy(value: string): PasswordRule[] {
  return [
    { key: 'length', label: LENGTH_LABEL, valid: value.length >= 10 },
    { key: 'uppercase', label: UPPERCASE_LABEL, valid: /[A-Z]/.test(value) },
    { key: 'special', label: SPECIAL_LABEL, valid: /[^A-Za-z0-9]/.test(value) },
  ];
}

/** Convenience: `true` iff every rule from `evaluatePasswordPolicy` is satisfied. */
export function isPasswordPolicySatisfied(value: string): boolean {
  return evaluatePasswordPolicy(value).every((rule) => rule.valid);
}
