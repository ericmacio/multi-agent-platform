import { describe, expect, test } from 'vitest';
import { updateConversationSchema } from './schema';

describe('updateConversationSchema', () => {
  test('rejects empty title', () => {
    expect(updateConversationSchema.safeParse({ title: '' }).success).toBe(false);
  });

  test('rejects title longer than 32 chars', () => {
    expect(updateConversationSchema.safeParse({ title: 'x'.repeat(33) }).success).toBe(false);
  });

  test('accepts title at the 32-char boundary', () => {
    expect(updateConversationSchema.safeParse({ title: 'x'.repeat(32) }).success).toBe(true);
  });

  test('accepts a normal title', () => {
    expect(updateConversationSchema.safeParse({ title: 'My chat' }).success).toBe(true);
  });
});
