import { describe, expect, test } from 'vitest';
import { parseSseFrame } from './sseFrames';

describe('parseSseFrame', () => {
  test('parses a `started` frame', () => {
    const r = parseSseFrame(
      'started',
      JSON.stringify({ userMessageId: 'u1', conversationId: 'c1' }),
    );
    expect(r.ok).toBe(true);
    if (r.ok && r.value.type === 'started') {
      expect(r.value.userMessageId).toBe('u1');
      expect(r.value.conversationId).toBe('c1');
    }
  });

  test('parses a `delta` frame', () => {
    const r = parseSseFrame('delta', JSON.stringify({ text: 'hello' }));
    expect(r.ok).toBe(true);
    if (r.ok && r.value.type === 'delta') {
      expect(r.value.text).toBe('hello');
    }
  });

  test('parses a `completed` frame with a non-null title', () => {
    const r = parseSseFrame(
      'completed',
      JSON.stringify({ assistantMessageId: 'a1', title: 'Hello!', messageCount: 2 }),
    );
    expect(r.ok).toBe(true);
    if (r.ok && r.value.type === 'completed') {
      expect(r.value.title).toBe('Hello!');
      expect(r.value.messageCount).toBe(2);
    }
  });

  test('parses a `completed` frame with a null title', () => {
    const r = parseSseFrame(
      'completed',
      JSON.stringify({ assistantMessageId: 'a1', title: null, messageCount: 4 }),
    );
    expect(r.ok).toBe(true);
    if (r.ok && r.value.type === 'completed') {
      expect(r.value.title).toBeNull();
    }
  });

  test('parses an `error` frame (loose validation — any object body)', () => {
    const r = parseSseFrame(
      'error',
      JSON.stringify({ title: 'LLM down', status: 502, code: 'LLM_UNAVAILABLE' }),
    );
    expect(r.ok).toBe(true);
    if (r.ok && r.value.type === 'error') {
      expect(r.value.problem['code']).toBe('LLM_UNAVAILABLE');
    }
  });

  test('rejects an unknown event name as "unknown-type"', () => {
    const r = parseSseFrame('mystery', JSON.stringify({}));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toBe('unknown-type');
  });

  test('rejects malformed JSON data as "malformed-data"', () => {
    const r = parseSseFrame('started', 'not-json');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toBe('malformed-data');
  });

  test('rejects a started frame missing required fields as "malformed-data"', () => {
    const r = parseSseFrame('started', JSON.stringify({ userMessageId: 'u1' }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toBe('malformed-data');
  });
});
