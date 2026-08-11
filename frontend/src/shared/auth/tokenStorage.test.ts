import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { tokenStorage, type TokenBundle } from './tokenStorage';

const KEY = 'mam.token';

function aBundle(overrides: Partial<TokenBundle> = {}): TokenBundle {
  return {
    token: 'header.payload.sig',
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    mustChangePassword: false,
    ...overrides,
  };
}

describe('tokenStorage', () => {
  beforeEach(() => {
    sessionStorage.clear();
    tokenStorage.clear();
  });

  afterEach(() => {
    sessionStorage.clear();
    tokenStorage.clear();
  });

  test('set() / get() round-trip via the in-memory closure', () => {
    const bundle = aBundle();
    tokenStorage.set(bundle);
    expect(tokenStorage.get()).toEqual(bundle);
  });

  test('set() writes the bundle as JSON to sessionStorage', () => {
    const bundle = aBundle({ token: 'persist-me' });
    tokenStorage.set(bundle);
    const raw = sessionStorage.getItem(KEY);
    expect(raw).not.toBeNull();
    expect(JSON.parse(raw!)).toEqual(bundle);
  });

  test('clear() empties both memory and sessionStorage', () => {
    tokenStorage.set(aBundle());
    expect(tokenStorage.get()).not.toBeNull();
    expect(sessionStorage.getItem(KEY)).not.toBeNull();

    tokenStorage.clear();
    expect(tokenStorage.get()).toBeNull();
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  test('hydrateFromSession() restores memory and deletes the sessionStorage entry', () => {
    const bundle = aBundle({ token: 'from-reload' });
    sessionStorage.setItem(KEY, JSON.stringify(bundle));

    const restored = tokenStorage.hydrateFromSession();
    expect(restored).toEqual(bundle);
    expect(tokenStorage.get()).toEqual(bundle);
    // One-shot hand-off: the sessionStorage entry MUST be gone afterwards so
    // the token never lives in two stores at once.
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  test('hydrateFromSession() returns null when nothing is stored', () => {
    expect(tokenStorage.hydrateFromSession()).toBeNull();
    expect(tokenStorage.get()).toBeNull();
  });

  test('hydrateFromSession() discards a malformed JSON entry', () => {
    sessionStorage.setItem(KEY, 'not valid json');
    expect(tokenStorage.hydrateFromSession()).toBeNull();
    expect(tokenStorage.get()).toBeNull();
    // The corrupt entry is consumed so it does not fail again on next boot.
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  test('hydrateFromSession() discards a payload missing required keys', () => {
    sessionStorage.setItem(KEY, JSON.stringify({ token: 'only-token' }));
    expect(tokenStorage.hydrateFromSession()).toBeNull();
    expect(tokenStorage.get()).toBeNull();
  });

  test('hydrateFromSession() discards a payload whose `mustChangePassword` is not boolean', () => {
    sessionStorage.setItem(
      KEY,
      JSON.stringify({ token: 't', expiresAt: 'x', mustChangePassword: 'yes' }),
    );
    expect(tokenStorage.hydrateFromSession()).toBeNull();
  });
});
