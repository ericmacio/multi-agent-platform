import { describe, expect, test } from 'vitest';
import { sendMessageSchema } from './composer.schema';

describe('sendMessageSchema', () => {
  test('rejects empty content (min)', () => {
    expect(sendMessageSchema.safeParse({ content: '' }).success).toBe(false);
  });

  test('rejects 1025-char content (max)', () => {
    expect(sendMessageSchema.safeParse({ content: 'x'.repeat(1025) }).success).toBe(false);
  });

  test('accepts a valid 1-char content', () => {
    expect(sendMessageSchema.safeParse({ content: 'h' }).success).toBe(true);
  });

  test('accepts a valid 1024-char content', () => {
    expect(sendMessageSchema.safeParse({ content: 'x'.repeat(1024) }).success).toBe(true);
  });
});
