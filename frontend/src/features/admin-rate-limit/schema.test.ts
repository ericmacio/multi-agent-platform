import { describe, expect, test } from 'vitest';
import { rateLimitConfigSchema } from './schema';

describe('rateLimitConfigSchema', () => {
  test('accepts a typical value', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: 60, perHour: 3600 });
    expect(result.success).toBe(true);
  });

  test('accepts the min=1 boundary for both fields', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: 1, perHour: 1 });
    expect(result.success).toBe(true);
  });

  test('rejects perMinute=0 with a min error on perMinute', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: 0, perHour: 3600 });
    expect(result.success).toBe(false);
    if (!result.success) {
      const perMinuteIssue = result.error.issues.find((i) => i.path[0] === 'perMinute');
      expect(perMinuteIssue?.code).toBe('too_small');
    }
  });

  test('rejects perHour=0 with a min error on perHour', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: 60, perHour: 0 });
    expect(result.success).toBe(false);
    if (!result.success) {
      const perHourIssue = result.error.issues.find((i) => i.path[0] === 'perHour');
      expect(perHourIssue?.code).toBe('too_small');
    }
  });

  test('rejects a non-integer perMinute with the integer error code', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: 1.5, perHour: 3600 });
    expect(result.success).toBe(false);
    if (!result.success) {
      const perMinuteIssue = result.error.issues.find((i) => i.path[0] === 'perMinute');
      // Zod surfaces integer violations either as `invalid_type` or `not_integer`
      // depending on version — assert on the message to stay resilient.
      expect(perMinuteIssue).toBeDefined();
      expect(perMinuteIssue?.message.toLowerCase()).toMatch(/integer/);
    }
  });

  test('rejects a negative perMinute', () => {
    const result = rateLimitConfigSchema.safeParse({ perMinute: -1, perHour: 3600 });
    expect(result.success).toBe(false);
    if (!result.success) {
      const perMinuteIssue = result.error.issues.find((i) => i.path[0] === 'perMinute');
      expect(perMinuteIssue).toBeDefined();
    }
  });

  test('rejects an empty payload with both field errors', () => {
    const result = rateLimitConfigSchema.safeParse({});
    expect(result.success).toBe(false);
    if (!result.success) {
      const fields = new Set(result.error.issues.map((i) => i.path[0]));
      expect(fields.has('perMinute')).toBe(true);
      expect(fields.has('perHour')).toBe(true);
    }
  });
});
