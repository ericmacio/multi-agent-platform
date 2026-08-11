import { describe, expect, test } from 'vitest';
import { evaluatePasswordPolicy, isPasswordPolicySatisfied, passwordPolicy } from './password';

describe('passwordPolicy schema', () => {
  test('rejects an empty string with the length message first', () => {
    const r = passwordPolicy.safeParse('');
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues[0]?.message).toBe('At least 10 characters');
    }
  });

  test('rejects a password under 10 characters', () => {
    const r = passwordPolicy.safeParse('Abc!');
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues.some((i) => i.message === 'At least 10 characters')).toBe(true);
    }
  });

  test('rejects a 10+ char password without an uppercase letter', () => {
    const r = passwordPolicy.safeParse('abcdefghij!');
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues.some((i) => i.message === 'At least one uppercase letter')).toBe(true);
    }
  });

  test('rejects a 10+ char password without a special character', () => {
    const r = passwordPolicy.safeParse('Abcdefghij');
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues.some((i) => i.message === 'At least one special character')).toBe(true);
    }
  });

  test('accepts a compliant password', () => {
    const r = passwordPolicy.safeParse('Abcdefghij!');
    expect(r.success).toBe(true);
  });

  test('rejects a password longer than 256 characters', () => {
    const tooLong = 'A' + 'a'.repeat(256) + '!';
    const r = passwordPolicy.safeParse(tooLong);
    expect(r.success).toBe(false);
  });
});

describe('evaluatePasswordPolicy', () => {
  test('returns three rules in stable [length, uppercase, special] order', () => {
    const keys = evaluatePasswordPolicy('').map((r) => r.key);
    expect(keys).toEqual(['length', 'uppercase', 'special']);
  });

  test('every rule is `valid: false` for an empty string', () => {
    expect(evaluatePasswordPolicy('').every((r) => !r.valid)).toBe(true);
  });

  test('every rule is `valid: true` for a fully-compliant password', () => {
    expect(evaluatePasswordPolicy('Abcdefghij!').every((r) => r.valid)).toBe(true);
  });

  test('uppercase rule alone passes for a single uppercase letter', () => {
    const rules = evaluatePasswordPolicy('A');
    const byKey = Object.fromEntries(rules.map((r) => [r.key, r.valid]));
    expect(byKey).toEqual({ length: false, uppercase: true, special: false });
  });

  test('length + uppercase pass but special does not for `Abcdefghi` (9 chars + uppercase, no special)', () => {
    const rules = evaluatePasswordPolicy('Abcdefghi');
    const byKey = Object.fromEntries(rules.map((r) => [r.key, r.valid]));
    expect(byKey).toEqual({ length: false, uppercase: true, special: false });
  });

  test('rule labels match the Zod schema messages byte-for-byte', () => {
    const rules = evaluatePasswordPolicy('');
    expect(rules.find((r) => r.key === 'length')?.label).toBe('At least 10 characters');
    expect(rules.find((r) => r.key === 'uppercase')?.label).toBe('At least one uppercase letter');
    expect(rules.find((r) => r.key === 'special')?.label).toBe('At least one special character');
  });
});

describe('isPasswordPolicySatisfied', () => {
  test('returns true for a compliant password', () => {
    expect(isPasswordPolicySatisfied('Abcdefghij!')).toBe(true);
  });

  test('returns false when any rule fails', () => {
    expect(isPasswordPolicySatisfied('abcdefghij')).toBe(false);
    expect(isPasswordPolicySatisfied('Abcdefghij')).toBe(false);
    expect(isPasswordPolicySatisfied('Abc!')).toBe(false);
    expect(isPasswordPolicySatisfied('')).toBe(false);
  });
});
