import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { server } from '@/test/server';
import { env } from '@/env';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { ToastViewport, _resetToasts } from '@/shared/ui/Toast';
import { routes } from './routes';

const BASE = env.VITE_API_BASE_URL;

function base64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(role: 'ADMIN' | 'STANDARD' = 'STANDARD'): string {
  return [
    base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64url(
      JSON.stringify({
        sub: 'alice@example.com',
        role,
        iat: Math.floor(Date.now() / 1000),
        jti: 'j1',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ),
    'sig',
  ].join('.');
}
function aBundle(
  overrides: Partial<TokenBundle> & { role?: 'ADMIN' | 'STANDARD' } = {},
): TokenBundle {
  const { role, ...rest } = overrides;
  return {
    token: makeJwt(role ?? 'STANDARD'),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
    ...rest,
  };
}

/**
 * The HomePage dashboard (route `/`) fetches agents, conversations, tools and
 * MCP servers on mount. Tests that visit `/` must stub all four to keep MSW's
 * `onUnhandledRequest: 'error'` quiet.
 */
function homePageStubHandlers() {
  const emptyPage = () =>
    HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
  const emptyList = () => HttpResponse.json({ items: [] });
  return [
    http.get(`${BASE}/agents`, emptyPage),
    http.get(`${BASE}/conversations`, emptyPage),
    http.get(`${BASE}/tools`, emptyList),
    http.get(`${BASE}/mcp-servers`, emptyList),
  ];
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderRouterAt(initialEntries: string[]) {
  const memoryRouter = createMemoryRouter(routes, { initialEntries });
  return render(
    <QueryClientProvider client={freshClient()}>
      <AuthProvider>
        <RouterProvider router={memoryRouter} />
        <ToastViewport />
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

describe('routes integration', () => {
  test('un-authed visit to / redirects to /login?next=/', async () => {
    renderRouterAt(['/']);
    // RequireAuth redirects to /login?next=/.
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument(),
    );
  });

  test('first-time-admin: authed at / with mustChangePassword=true → /change-password?reason=forced', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    renderRouterAt(['/']);
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 1, name: /change password/i }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByTestId('forced-banner')).toBeInTheDocument();
  });

  test('already-authed visiting /login redirects to the dashboard', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: false }));
    // HomePage dashboard fetches agents / conversations / tools / mcp-servers
    // on mount — stub all four to keep MSW quiet under onUnhandledRequest.
    server.use(...homePageStubHandlers());
    renderRouterAt(['/login']);
    // RequireGuest redirects the authed visitor to /. The greeting heading
    // is the most stable anchor that the dashboard resolved correctly.
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 1, name: /^good (morning|afternoon|evening|night)$/i }),
      ).toBeInTheDocument(),
    );
  });

  test('already-authed visiting /change-password renders the page (no redirect loop)', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: false }));
    renderRouterAt(['/change-password']);
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 1, name: /change password/i }),
      ).toBeInTheDocument(),
    );
    // Self-initiated visit: no forced banner.
    expect(screen.queryByTestId('forced-banner')).not.toBeInTheDocument();
  });

  test('logout flow end-to-end: profile menu → sign out → /login', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: false }));
    server.use(
      http.post(`${BASE}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
      ...homePageStubHandlers(),
    );

    renderRouterAt(['/']);
    // Authed user at / sees the HomePage dashboard under AppShell, which renders Topbar.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /profile menu/i })).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole('button', { name: /profile menu/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /sign out/i }));

    // After the logout mutation settles, signOut('/login') runs and
    // AuthRedirector navigates to /login.
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument(),
    );
    expect(tokenStorage.get()).toBeNull();
  });

  test('STANDARD user visiting /admin/users bounces to /403', async () => {
    tokenStorage.set(aBundle({ role: 'STANDARD' }));
    renderRouterAt(['/admin/users']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /403.*forbidden/i })).toBeInTheDocument(),
    );
  });

  test('ADMIN user visiting /admin/users lands on the AdminUsersPage', async () => {
    tokenStorage.set(aBundle({ role: 'ADMIN' }));
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );
    renderRouterAt(['/admin/users']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /^users$/i })).toBeInTheDocument(),
    );
  });

  test('ADMIN user visiting /admin/users/new lands on the AdminUserCreatePage', async () => {
    tokenStorage.set(aBundle({ role: 'ADMIN' }));
    renderRouterAt(['/admin/users/new']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /new user/i })).toBeInTheDocument(),
    );
  });

  test('ADMIN user visiting /admin/users/:id lands on the AdminUserDetailPage', async () => {
    tokenStorage.set(aBundle({ role: 'ADMIN' }));
    // Detail page fetches the user; MSW must respond so isPending resolves.
    server.use(
      http.get(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json({
          id: 'abc',
          email: 'x@y.io',
          role: 'STANDARD',
          disabled: false,
          mustChangePassword: false,
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:00Z',
        }),
      ),
    );
    renderRouterAt(['/admin/users/abc']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'x@y.io' })).toBeInTheDocument(),
    );
  });

  test('STANDARD user visiting /admin/api-keys bounces to /403', async () => {
    tokenStorage.set(aBundle({ role: 'STANDARD' }));
    renderRouterAt(['/admin/api-keys']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /403.*forbidden/i })).toBeInTheDocument(),
    );
  });

  test('ADMIN user visiting /admin/api-keys lands on the AdminApiKeysPage', async () => {
    tokenStorage.set(aBundle({ role: 'ADMIN' }));
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );
    renderRouterAt(['/admin/api-keys']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /^api keys$/i })).toBeInTheDocument(),
    );
  });

  test('STANDARD user visiting /admin/rate-limit bounces to /403', async () => {
    tokenStorage.set(aBundle({ role: 'STANDARD' }));
    renderRouterAt(['/admin/rate-limit']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /403.*forbidden/i })).toBeInTheDocument(),
    );
  });

  test('ADMIN user visiting /admin/rate-limit lands on the AdminRateLimitPage', async () => {
    tokenStorage.set(aBundle({ role: 'ADMIN' }));
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json({
          perMinute: 60,
          perHour: 3600,
          updatedAt: '2026-01-01T00:00:00Z',
          updatedBy: 'admin-uuid-1',
        }),
      ),
    );
    renderRouterAt(['/admin/rate-limit']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /^rate limit$/i })).toBeInTheDocument(),
    );
  });

  test('unauthenticated visit to /admin/users redirects to /login?next=/admin/users', async () => {
    renderRouterAt(['/admin/users']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument(),
    );
  });

  test('logout tolerates a 500 from the server: local state still cleared', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: false }));
    server.use(
      http.post(`${BASE}/auth/logout`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
      ...homePageStubHandlers(),
    );

    renderRouterAt(['/']);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /profile menu/i })).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole('button', { name: /profile menu/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /sign out/i }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument(),
    );
    expect(tokenStorage.get()).toBeNull();
  });
});
