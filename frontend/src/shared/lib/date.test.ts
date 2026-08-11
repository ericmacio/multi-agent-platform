import { describe, expect, test } from 'vitest';
import { formatDateTime, formatRelative, isExpired } from './date';

describe('formatDateTime', () => {
  test('formats a valid ISO timestamp with medium date + short time', () => {
    const out = formatDateTime('2026-06-03T14:05:00Z', 'en-US');
    // Output is locale-driven; assert the year + a discernible time component
    // rather than the exact punctuation (which varies across ICU versions).
    expect(out).toMatch(/2026/);
    expect(out).toMatch(/Jun/);
  });

  test('returns the em-dash placeholder for an unparseable input', () => {
    expect(formatDateTime('not a date')).toBe('—');
  });

  test('returns the em-dash placeholder for an empty input', () => {
    expect(formatDateTime('')).toBe('—');
  });
});

describe('formatRelative', () => {
  const now = new Date('2026-06-03T12:00:00Z');

  test('returns "now" for sub-second deltas', () => {
    expect(formatRelative('2026-06-03T12:00:00Z', now)).toBe('now');
  });

  test('picks seconds for deltas under a minute', () => {
    const out = formatRelative('2026-06-03T11:59:30Z', now, 'en-US');
    expect(out).toMatch(/30 seconds ago/);
  });

  test('picks minutes for deltas under an hour', () => {
    const out = formatRelative('2026-06-03T11:55:00Z', now, 'en-US');
    expect(out).toMatch(/5 minutes ago/);
  });

  test('picks hours for deltas under a day', () => {
    const out = formatRelative('2026-06-03T09:00:00Z', now, 'en-US');
    expect(out).toMatch(/3 hours ago/);
  });

  test('picks days for deltas under a week', () => {
    const out = formatRelative('2026-06-01T12:00:00Z', now, 'en-US');
    expect(out).toMatch(/2 days ago/);
  });

  test('handles future timestamps with the appropriate sign', () => {
    const out = formatRelative('2026-06-03T12:05:00Z', now, 'en-US');
    expect(out).toMatch(/in 5 minutes/);
  });

  test('returns the em-dash placeholder for an unparseable input', () => {
    expect(formatRelative('not a date', now)).toBe('—');
  });
});

describe('isExpired', () => {
  const now = new Date('2026-06-03T12:00:00Z');
  const nowEpochSeconds = Math.floor(now.getTime() / 1000);

  test('returns true when the epoch is strictly in the past', () => {
    expect(isExpired(nowEpochSeconds - 1, now)).toBe(true);
  });

  test('returns false when the epoch equals now', () => {
    expect(isExpired(nowEpochSeconds, now)).toBe(false);
  });

  test('returns false when the epoch is in the future', () => {
    expect(isExpired(nowEpochSeconds + 60, now)).toBe(false);
  });
});
