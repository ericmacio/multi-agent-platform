import { describe, expect, test } from 'vitest';
import { createApiKeySchema } from './schema';

describe('createApiKeySchema', () => {
  test('accepts an omitted label', () => {
    const result = createApiKeySchema.safeParse({});
    expect(result.success).toBe(true);
  });

  test('accepts a short label', () => {
    const result = createApiKeySchema.safeParse({ label: 'CI pipeline' });
    expect(result.success).toBe(true);
  });

  test('accepts a label at the 128-char boundary', () => {
    const result = createApiKeySchema.safeParse({ label: 'x'.repeat(128) });
    expect(result.success).toBe(true);
  });

  test('rejects a label longer than 128 chars', () => {
    const result = createApiKeySchema.safeParse({ label: 'x'.repeat(129) });
    expect(result.success).toBe(false);
  });
});
