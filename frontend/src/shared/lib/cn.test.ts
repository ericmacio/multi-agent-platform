import { describe, expect, test } from 'vitest';
import { cn } from './cn';

describe('cn', () => {
  test('returns an empty string when called with no inputs', () => {
    expect(cn()).toBe('');
  });

  test('joins class names', () => {
    expect(cn('a', 'b', 'c')).toBe('a b c');
  });

  test('resolves Tailwind utility conflicts with last-wins semantics', () => {
    // tailwind-merge contract: `p-4` overrides `p-2`.
    expect(cn('p-2', 'p-4')).toBe('p-4');
  });

  test('preserves non-conflicting Tailwind utilities', () => {
    expect(cn('p-2', 'm-4')).toBe('p-2 m-4');
  });

  test('honors clsx conditional inputs (falsy values dropped)', () => {
    expect(cn('a', false && 'b', null, undefined, 0, '', 'c')).toBe('a c');
  });

  test('flattens arrays and accepts conditional record form', () => {
    expect(cn(['a', 'b'], { c: true, d: false })).toBe('a b c');
  });
});
