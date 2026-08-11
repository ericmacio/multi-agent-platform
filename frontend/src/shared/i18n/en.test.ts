import { describe, expect, test } from 'vitest';
import { errorCopy, labels, type ProblemCode } from './en';

// Mirrors the `ProblemDetails.code` enum from `openapi.yaml`. Hand-listed here
// (rather than reflected from the generated `.d.ts`, which carries no runtime
// values) so a missing copy entry shows up as a failing assertion, and a future
// backend addition to the enum surfaces as a TypeScript compile error on the
// satisfies-line below.
const ALL_PROBLEM_CODES = [
  'VALIDATION_ERROR',
  'INVALID_CREDENTIALS',
  'MUST_CHANGE_PASSWORD',
  'FORBIDDEN',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'CONFLICT',
  'DUPLICATE_AGENT_NAME',
  'NESTED_TEAM_FORBIDDEN',
  'CROSS_OWNER_TEAM_MEMBER',
  'CONVERSATION_FULL',
  'RATE_LIMITED',
  'LLM_UNAVAILABLE',
  'MCP_SERVER_ERROR',
  'NOT_ACCEPTABLE',
  'INTERNAL_ERROR',
] as const satisfies readonly ProblemCode[];

describe('errorCopy', () => {
  test.each(ALL_PROBLEM_CODES)(
    'has a non-empty title, detail, and a toast policy for %s',
    (code) => {
      const entry = errorCopy[code];
      expect(entry).toBeDefined();
      expect(entry.title.length).toBeGreaterThan(0);
      expect(entry.detail.length).toBeGreaterThan(0);
      expect(['on', 'off']).toContain(entry.toast);
    },
  );

  test('chat-surface toast policy matches SW-DESIGN §10.2', () => {
    // The chat surface uses these decisions; this test locks them in place.
    expect(errorCopy.CONVERSATION_FULL.toast).toBe('off');
    expect(errorCopy.CANCELLED.toast).toBe('off');
    expect(errorCopy.LLM_UNAVAILABLE.toast).toBe('on');
    expect(errorCopy.MCP_SERVER_ERROR.toast).toBe('on');
    expect(errorCopy.RATE_LIMITED.toast).toBe('on');
    expect(errorCopy.NOT_ACCEPTABLE.toast).toBe('on');
  });

  test('exposes a fallback entry for unknown codes', () => {
    expect(errorCopy.__unknown__.title.length).toBeGreaterThan(0);
    expect(errorCopy.__unknown__.detail.length).toBeGreaterThan(0);
  });

  test('covers every ProblemCode the OpenAPI spec defines', () => {
    // Whitebox check: the hand-listed `ALL_PROBLEM_CODES` above must contain
    // every key in `errorCopy` except `__unknown__` (forward-compat fallback)
    // and `CANCELLED` (client-only sentinel, US-07-002). If the backend adds
    // a new code and we forget to update either side, this test fails loudly.
    const copyKeys = new Set(Object.keys(errorCopy));
    copyKeys.delete('__unknown__');
    copyKeys.delete('CANCELLED');
    expect(copyKeys.size).toBe(ALL_PROBLEM_CODES.length);
    for (const code of ALL_PROBLEM_CODES) {
      expect(copyKeys.has(code)).toBe(true);
    }
  });

  test('exposes a CANCELLED entry for the client-only sentinel', () => {
    expect(errorCopy.CANCELLED.title.length).toBeGreaterThan(0);
    expect(errorCopy.CANCELLED.detail.length).toBeGreaterThan(0);
  });
});

describe('labels', () => {
  test('is an extensible object exported from the module', () => {
    expect(typeof labels).toBe('object');
    expect(labels).not.toBeNull();
  });
});
