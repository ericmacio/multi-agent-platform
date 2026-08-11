import { describe, expect, test } from 'vitest';
import { decodeJwtPayload } from './jwt';

function base64url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeToken(payload: Record<string, unknown>): string {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

const VALID_PAYLOAD = {
  sub: 'alice@example.com',
  role: 'STANDARD' as const,
  exp: 1_900_000_000,
  iat: 1_800_000_000,
  jti: 'jti-1',
};

describe('decodeJwtPayload', () => {
  test('round-trips a well-formed payload', () => {
    const r = decodeJwtPayload(makeToken(VALID_PAYLOAD));
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.value).toEqual(VALID_PAYLOAD);
    }
  });

  test('preserves the ADMIN role', () => {
    const r = decodeJwtPayload(makeToken({ ...VALID_PAYLOAD, role: 'ADMIN' }));
    expect(r.ok && r.value.role).toBe('ADMIN');
  });

  test('rejects a non-three-segment token as malformed', () => {
    const r = decodeJwtPayload('not.a.jwt.token');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('malformed');
  });

  test('rejects an empty string as malformed', () => {
    const r = decodeJwtPayload('');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('malformed');
  });

  test('rejects a token whose payload segment is not valid JSON', () => {
    const r = decodeJwtPayload(['header', base64url('not-json'), 'sig'].join('.'));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('malformed');
  });

  test('rejects a payload missing required fields as invalid-payload', () => {
    const { role: _omit, ...missingRole } = VALID_PAYLOAD;
    const r = decodeJwtPayload(makeToken(missingRole));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('invalid-payload');
  });

  test('rejects an unknown role value as invalid-payload', () => {
    const r = decodeJwtPayload(makeToken({ ...VALID_PAYLOAD, role: 'SUPER_ADMIN' }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('invalid-payload');
  });

  test('rejects a payload with a non-numeric exp as invalid-payload', () => {
    const r = decodeJwtPayload(makeToken({ ...VALID_PAYLOAD, exp: 'soon' }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.reason).toBe('invalid-payload');
  });
});
