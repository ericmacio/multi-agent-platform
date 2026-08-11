import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode } from 'react';
import { renderWithProviders } from '@/test/render';
import { tokenStorage, type TokenBundle } from './tokenStorage';
import { AuthProvider, useAuth } from './AuthContext';

function base64url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(
  overrides: Partial<{ sub: string; role: 'ADMIN' | 'STANDARD'; exp: number }> = {},
): string {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64url(
    JSON.stringify({
      sub: overrides.sub ?? 'alice@example.com',
      role: overrides.role ?? 'STANDARD',
      iat: Math.floor(Date.now() / 1000),
      jti: 'jti-1',
      exp: overrides.exp ?? Math.floor(Date.now() / 1000) + 3600,
    }),
  );
  return `${header}.${body}.sig`;
}

function aBundle(overrides: Partial<TokenBundle> = {}): TokenBundle {
  return {
    token: makeJwt(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
    ...overrides,
  };
}

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter>{children}</MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>
    );
  };
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

/** Probe the current MemoryRouter location for navigation assertions. */
function LocationProbe(): JSX.Element {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

describe('AuthProvider', () => {
  beforeEach(() => {
    tokenStorage.clear();
  });
  afterEach(() => {
    tokenStorage.clear();
    vi.useRealTimers();
  });

  test('initial state is empty when tokenStorage is empty', () => {
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.token).toBeNull();
    expect(result.current.principal).toBeNull();
    expect(result.current.mustChangePassword).toBe(false);
  });

  test('initial state hydrates from a pre-populated tokenStorage bundle', () => {
    const bundle = aBundle({ mustChangePassword: true });
    tokenStorage.set(bundle);
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.token).toBe(bundle.token);
    expect(result.current.principal?.sub).toBe('alice@example.com');
    expect(result.current.principal?.role).toBe('STANDARD');
    expect(result.current.mustChangePassword).toBe(true);
  });

  test('useAuth throws when used outside <AuthProvider>', () => {
    // Suppress React's error log for this expected throw.
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => renderHook(() => useAuth())).toThrow(/within <AuthProvider>/);
    errSpy.mockRestore();
  });

  test('signIn updates state and persists the bundle', () => {
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    const bundle = aBundle({ token: makeJwt({ sub: 'admin@example.com', role: 'ADMIN' }) });
    act(() => result.current.signIn(bundle));
    expect(result.current.token).toBe(bundle.token);
    expect(result.current.principal?.role).toBe('ADMIN');
    expect(tokenStorage.get()).toEqual(bundle);
  });

  test('signIn throws (without mutating state) when the JWT cannot be decoded', () => {
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(() =>
      act(() =>
        result.current.signIn({
          token: 'not-a-jwt',
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          mustChangePassword: false,
        }),
      ),
    ).toThrow(/JWT/);
    expect(result.current.token).toBeNull();
    expect(tokenStorage.get()).toBeNull();
  });

  test('signOut clears state and tokenStorage', async () => {
    tokenStorage.set(aBundle());
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.token).not.toBeNull();
    await act(async () => {
      await result.current.signOut();
    });
    expect(result.current.token).toBeNull();
    expect(result.current.principal).toBeNull();
    expect(tokenStorage.get()).toBeNull();
  });

  test('expiry-warning timer flips the flag 30 s before exp', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-03T12:00:00Z'));
    const expIso = new Date('2026-06-03T12:01:00Z').toISOString(); // 60s from now
    const expSec = Math.floor(Date.parse(expIso) / 1000);
    tokenStorage.set(aBundle({ token: makeJwt({ exp: expSec }), expiresAt: expIso }));

    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.expiryWarning).toBe(false);

    // Advance past the trigger (exp - 30s = +30s from now).
    act(() => {
      vi.advanceTimersByTime(30_001);
    });
    expect(result.current.expiryWarning).toBe(true);

    act(() => result.current.dismissExpiryWarning());
    expect(result.current.expiryWarning).toBe(false);

    // Advancing further does NOT re-trigger (one-shot timer).
    act(() => {
      vi.advanceTimersByTime(25_000);
    });
    expect(result.current.expiryWarning).toBe(false);
  });

  test('expiry warning fires immediately when exp is already within 30 s', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-03T12:00:00Z'));
    const expIso = new Date('2026-06-03T12:00:10Z').toISOString(); // 10s from now
    const expSec = Math.floor(Date.parse(expIso) / 1000);
    tokenStorage.set(aBundle({ token: makeJwt({ exp: expSec }), expiresAt: expIso }));

    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.expiryWarning).toBe(true);
  });

  test('auth:logout event clears state and queues a /login redirect with next', async () => {
    tokenStorage.set(aBundle());
    renderWithProviders(<LocationProbe />, { initialEntries: ['/agents?cursor=abc'] });

    expect(screen.getByTestId('location').textContent).toBe('/agents?cursor=abc');

    await act(async () => {
      window.dispatchEvent(
        new CustomEvent('auth:logout', { detail: { reason: 'token-rejected' } }),
      );
    });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe(
        `/login?next=${encodeURIComponent('/agents?cursor=abc')}`,
      );
    });
    expect(tokenStorage.get()).toBeNull();
  });

  test('AuthRedirector consumes redirectTo and clears the intent', async () => {
    tokenStorage.set(aBundle());
    const { result } = renderHook(() => useAuth(), { wrapper: wrapperFor(freshClient()) });
    expect(result.current.redirectTo).toBeNull();

    await act(async () => {
      await result.current.signOut('/login');
    });
    expect(result.current.redirectTo).toBe('/login');

    // AuthRedirector is the consumer in routes; manually invoke clear here.
    act(() => result.current.clearRedirect());
    expect(result.current.redirectTo).toBeNull();
  });
});
