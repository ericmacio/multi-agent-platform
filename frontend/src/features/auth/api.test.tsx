import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { ApiError } from '@/shared/api/errors';
import { AuthProvider, useAuth } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { useChangeOwnPassword, useLogin, useLogout } from './api';

const BASE = 'http://localhost:8080/api/v1';

function base64url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(role: 'ADMIN' | 'STANDARD' = 'STANDARD'): string {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64url(
    JSON.stringify({
      sub: 'alice@example.com',
      role,
      iat: Math.floor(Date.now() / 1000),
      jti: 'j1',
      exp: Math.floor(Date.now() / 1000) + 3600,
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

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
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

/**
 * Helper hook: composes the hook-under-test with `useAuth()` so a single
 * `renderHook` call returns both pieces of state.
 */
function useLoginPlusAuth() {
  return { login: useLogin(), auth: useAuth() };
}
function useLogoutPlusAuth() {
  return { logout: useLogout(), auth: useAuth() };
}
function useChangePlusAuth() {
  return { change: useChangeOwnPassword(), auth: useAuth() };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('useLogin', () => {
  test('200 happy path: signs in and updates AuthContext', async () => {
    const bundle = aBundle({ mustChangePassword: false });
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json({
          token: bundle.token,
          tokenType: 'Bearer' as const,
          expiresAt: bundle.expiresAt,
          mustChangePassword: bundle.mustChangePassword,
        }),
      ),
    );
    const { result } = renderHook(useLoginPlusAuth, { wrapper: wrapperFor(freshClient()) });
    expect(result.current.auth.token).toBeNull();

    result.current.login.mutate({ email: 'alice@example.com', password: 'pw' });
    await waitFor(() => expect(result.current.login.isSuccess).toBe(true));

    expect(result.current.auth.token).toBe(bundle.token);
    expect(result.current.auth.mustChangePassword).toBe(false);
    expect(result.current.auth.principal?.sub).toBe('alice@example.com');
  });

  test('200 with mustChangePassword=true propagates to context', async () => {
    const bundle = aBundle({ mustChangePassword: true });
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json({
          token: bundle.token,
          tokenType: 'Bearer' as const,
          expiresAt: bundle.expiresAt,
          mustChangePassword: true,
        }),
      ),
    );
    const { result } = renderHook(useLoginPlusAuth, { wrapper: wrapperFor(freshClient()) });
    result.current.login.mutate({ email: 'admin@example.com', password: 'pw' });
    await waitFor(() => expect(result.current.login.isSuccess).toBe(true));
    expect(result.current.auth.mustChangePassword).toBe(true);
  });

  test('401 INVALID_CREDENTIALS: signIn is NOT called; auth state stays empty', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const { result } = renderHook(useLoginPlusAuth, { wrapper: wrapperFor(freshClient()) });

    result.current.login.mutate({ email: 'a@b.c', password: 'wrong' });
    await waitFor(() => expect(result.current.login.isError).toBe(true));

    expect(result.current.login.error).toBeInstanceOf(ApiError);
    expect(result.current.login.error?.code).toBe('INVALID_CREDENTIALS');
    expect(result.current.auth.token).toBeNull();
  });

  test('429 RATE_LIMITED surfaces retryAfterSeconds on the ApiError', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' },
          },
        ),
      ),
    );
    const { result } = renderHook(useLoginPlusAuth, { wrapper: wrapperFor(freshClient()) });
    result.current.login.mutate({ email: 'a@b.c', password: 'pw' });
    await waitFor(() => expect(result.current.login.isError).toBe(true));
    expect(result.current.login.error?.code).toBe('RATE_LIMITED');
    expect(result.current.login.error?.retryAfterSeconds).toBe(5);
  });
});

describe('useLogout', () => {
  test('204 happy path: clears auth state and queues a /login redirect', async () => {
    tokenStorage.set(aBundle());
    server.use(http.post(`${BASE}/auth/logout`, () => new HttpResponse(null, { status: 204 })));

    const { result } = renderHook(useLogoutPlusAuth, { wrapper: wrapperFor(freshClient()) });
    expect(result.current.auth.token).not.toBeNull();

    result.current.logout.mutate();
    await waitFor(() => expect(result.current.logout.isSuccess).toBe(true));

    expect(result.current.auth.token).toBeNull();
    expect(result.current.auth.redirectTo).toBe('/login');
    expect(tokenStorage.get()).toBeNull();
  });

  test('best-effort: a 500 from the server still settles successfully and clears local state', async () => {
    tokenStorage.set(aBundle());
    server.use(
      http.post(`${BASE}/auth/logout`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { result } = renderHook(useLogoutPlusAuth, { wrapper: wrapperFor(freshClient()) });
    result.current.logout.mutate();
    await waitFor(() => expect(result.current.logout.isSuccess).toBe(true));

    expect(result.current.logout.error).toBeNull();
    expect(result.current.auth.token).toBeNull();
    expect(result.current.auth.redirectTo).toBe('/login');
  });
});

describe('useChangeOwnPassword', () => {
  test('204 happy path: clears mustChangePassword while preserving the token', async () => {
    const bundle = aBundle({ mustChangePassword: true });
    tokenStorage.set(bundle);
    server.use(http.put(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 204 })));

    const { result } = renderHook(useChangePlusAuth, { wrapper: wrapperFor(freshClient()) });
    expect(result.current.auth.mustChangePassword).toBe(true);
    const originalToken = result.current.auth.token;

    result.current.change.mutate({ currentPassword: 'old', newPassword: 'Abcdefghij!' });
    await waitFor(() => expect(result.current.change.isSuccess).toBe(true));

    expect(result.current.auth.mustChangePassword).toBe(false);
    expect(result.current.auth.token).toBe(originalToken);
    expect(tokenStorage.get()?.mustChangePassword).toBe(false);
    expect(tokenStorage.get()?.token).toBe(originalToken);
  });

  test('400 VALIDATION_ERROR: fieldErrors are surfaced; auth state unchanged', async () => {
    const bundle = aBundle({ mustChangePassword: true });
    tokenStorage.set(bundle);
    server.use(
      http.put(`${BASE}/auth/password`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'newPassword', message: 'must include a special character' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { result } = renderHook(useChangePlusAuth, { wrapper: wrapperFor(freshClient()) });
    result.current.change.mutate({ currentPassword: 'old', newPassword: 'too-weak' });
    await waitFor(() => expect(result.current.change.isError).toBe(true));

    expect(result.current.change.error?.code).toBe('VALIDATION_ERROR');
    expect(result.current.change.error?.fieldErrors).toEqual({
      newPassword: 'must include a special character',
    });
    expect(result.current.auth.mustChangePassword).toBe(true);
  });
});
