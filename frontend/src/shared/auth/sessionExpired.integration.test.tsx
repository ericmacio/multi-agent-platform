import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { server } from '@/test/server';
import { api } from '@/shared/api/client';
import { AuthProvider, AuthRedirector } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { ToastViewport, _resetToasts } from '@/shared/ui/Toast';

const BASE = 'http://localhost:8080/api/v1';

function base64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(expSecondsFromNow: number): string {
  return [
    base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64url(
      JSON.stringify({
        sub: 'alice@example.com',
        role: 'STANDARD',
        iat: Math.floor(Date.now() / 1000),
        jti: 'j1',
        exp: Math.floor(Date.now() / 1000) + expSecondsFromNow,
      }),
    ),
    'sig',
  ].join('.');
}
function liveBundle(): TokenBundle {
  return {
    token: makeJwt(3600),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
  };
}
function expiredBundle(): TokenBundle {
  return {
    token: makeJwt(-60),
    expiresAt: new Date(Date.now() - 60_000).toISOString(),
    mustChangePassword: false,
  };
}

function LocationProbe(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function setup(initialPath = '/agents') {
  return render(
    <QueryClientProvider client={freshClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <LocationProbe />
          <AuthRedirector />
          <ToastViewport />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
});

describe('session-expired UX (via AuthRedirector)', () => {
  test('a real 401 with a seeded session: redirect to /login?next=... AND show toast', async () => {
    tokenStorage.set(liveBundle());
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json(
          { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    setup('/agents');

    // Trigger a request that will return 401. The middleware dispatches
    // `auth:logout(reason='token-rejected')` because tokenStorage was seeded.
    await api.GET('/tools').catch(() => undefined);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        `/login?next=${encodeURIComponent('/agents')}`,
      ),
    );
    expect(screen.getByTestId('toast-info')).toHaveTextContent(/session expired/i);
  });

  test('a proactive expired-token short-circuit: redirect WITHOUT toast', async () => {
    // No MSW handler — the middleware should short-circuit before fetch.
    tokenStorage.set(expiredBundle());

    setup('/agents');

    await api.GET('/tools').catch(() => undefined);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        `/login?next=${encodeURIComponent('/agents')}`,
      ),
    );
    // Proactive expiry uses `reason='token-expired'`; the in-app banner has
    // already warned, so no session-expired toast should appear.
    expect(screen.queryByTestId('toast-info')).not.toBeInTheDocument();
  });

  test('a 401 with NO seeded session (failed login attempt): no toast, no redirect', async () => {
    // tokenStorage is empty (beforeEach). The middleware skips the
    // auth-failure side effect because there's no session to invalidate.
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    setup('/login');

    await api
      .POST('/auth/login', { body: { email: 'a@b.c', password: 'x' } })
      .catch(() => undefined);

    // No toast, location unchanged.
    expect(screen.queryByTestId('toast-info')).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
  });
});
