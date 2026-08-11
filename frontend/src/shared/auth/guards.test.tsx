import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactElement, type ReactNode } from 'react';
import { makeTestQueryClient } from '@/test/render';
import { AuthProvider } from './AuthContext';
import { RequireAuth, RequireFreshPassword, RequireGuest, RequireRole } from './guards';
import { tokenStorage, type TokenBundle } from './tokenStorage';

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
      jti: 'jti-1',
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

function LocationProbe(): ReactElement {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}

function renderWithRoutes(
  routes: ReactNode,
  initialEntries: string[] = ['/'],
  bundle: TokenBundle | null = null,
): void {
  if (bundle) tokenStorage.set(bundle);
  else tokenStorage.clear();

  const client = makeTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={initialEntries}>
          <LocationProbe />
          <Routes>{routes}</Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const Protected = () => <div data-testid="content">protected</div>;
const Forbidden = () => <div data-testid="content">forbidden</div>;
const Login = () => <div data-testid="content">login</div>;
const Change = () => <div data-testid="content">change</div>;
const Agents = () => <div data-testid="content">agents</div>;
const Dashboard = () => <div data-testid="content">dashboard</div>;

describe('RequireAuth', () => {
  beforeEach(() => tokenStorage.clear());
  afterEach(() => tokenStorage.clear());

  test('redirects unauthenticated users to /login with the next param', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireAuth>
              <Protected />
            </RequireAuth>
          }
        />
        <Route path="/login" element={<Login />} />
      </>,
      ['/agents'],
      null,
    );
    expect(screen.getByTestId('location').textContent).toBe(
      `/login?next=${encodeURIComponent('/agents')}`,
    );
    expect(screen.getByTestId('content').textContent).toBe('login');
  });

  test('encodes query strings in the next param', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireAuth>
              <Protected />
            </RequireAuth>
          }
        />
        <Route path="/login" element={<Login />} />
      </>,
      ['/agents?cursor=abc&filter=x'],
      null,
    );
    expect(screen.getByTestId('location').textContent).toBe(
      `/login?next=${encodeURIComponent('/agents?cursor=abc&filter=x')}`,
    );
  });

  test('renders the wrapped element when the token is live', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireAuth>
              <Protected />
            </RequireAuth>
          }
        />
      </>,
      ['/agents'],
      aBundle(),
    );
    expect(screen.getByTestId('content').textContent).toBe('protected');
  });

  test('redirects when the bundle expiresAt is in the past', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireAuth>
              <Protected />
            </RequireAuth>
          }
        />
        <Route path="/login" element={<Login />} />
      </>,
      ['/agents'],
      aBundle({ expiresAt: new Date(Date.now() - 1000).toISOString() }),
    );
    expect(screen.getByTestId('content').textContent).toBe('login');
  });

  test('renders an <Outlet /> when used as a layout-route element', () => {
    renderWithRoutes(
      <Route element={<RequireAuth />}>
        <Route path="/agents" element={<Protected />} />
      </Route>,
      ['/agents'],
      aBundle(),
    );
    expect(screen.getByTestId('content').textContent).toBe('protected');
  });
});

describe('RequireRole', () => {
  beforeEach(() => tokenStorage.clear());
  afterEach(() => tokenStorage.clear());

  test('redirects to /403 when the role does not match', () => {
    renderWithRoutes(
      <>
        <Route
          path="/admin"
          element={
            <RequireRole role="ADMIN">
              <Protected />
            </RequireRole>
          }
        />
        <Route path="/403" element={<Forbidden />} />
      </>,
      ['/admin'],
      aBundle({ token: makeJwt('STANDARD') }),
    );
    expect(screen.getByTestId('location').textContent).toBe('/403');
    expect(screen.getByTestId('content').textContent).toBe('forbidden');
  });

  test('renders content when the role matches', () => {
    renderWithRoutes(
      <>
        <Route
          path="/admin"
          element={
            <RequireRole role="ADMIN">
              <Protected />
            </RequireRole>
          }
        />
      </>,
      ['/admin'],
      aBundle({ token: makeJwt('ADMIN') }),
    );
    expect(screen.getByTestId('content').textContent).toBe('protected');
  });

  test('redirects to /403 when there is no principal', () => {
    renderWithRoutes(
      <>
        <Route
          path="/admin"
          element={
            <RequireRole role="ADMIN">
              <Protected />
            </RequireRole>
          }
        />
        <Route path="/403" element={<Forbidden />} />
      </>,
      ['/admin'],
      null,
    );
    expect(screen.getByTestId('content').textContent).toBe('forbidden');
  });
});

describe('RequireFreshPassword', () => {
  beforeEach(() => tokenStorage.clear());
  afterEach(() => tokenStorage.clear());

  test('redirects to /change-password?reason=forced when mustChangePassword=true', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireFreshPassword>
              <Protected />
            </RequireFreshPassword>
          }
        />
        <Route path="/change-password" element={<Change />} />
      </>,
      ['/agents'],
      aBundle({ mustChangePassword: true }),
    );
    expect(screen.getByTestId('location').textContent).toBe('/change-password?reason=forced');
    expect(screen.getByTestId('content').textContent).toBe('change');
  });

  test('renders content when mustChangePassword=false', () => {
    renderWithRoutes(
      <>
        <Route
          path="/agents"
          element={
            <RequireFreshPassword>
              <Protected />
            </RequireFreshPassword>
          }
        />
      </>,
      ['/agents'],
      aBundle({ mustChangePassword: false }),
    );
    expect(screen.getByTestId('content').textContent).toBe('protected');
  });
});

describe('RequireGuest', () => {
  beforeEach(() => tokenStorage.clear());
  afterEach(() => tokenStorage.clear());

  test('redirects authed users to the dashboard (/)', () => {
    renderWithRoutes(
      <>
        <Route
          path="/login"
          element={
            <RequireGuest>
              <Login />
            </RequireGuest>
          }
        />
        <Route path="/" element={<Dashboard />} />
      </>,
      ['/login'],
      aBundle(),
    );
    expect(screen.getByTestId('location').textContent).toBe('/');
    expect(screen.getByTestId('content').textContent).toBe('dashboard');
  });

  test('renders the login page when there is no token', () => {
    renderWithRoutes(
      <>
        <Route
          path="/login"
          element={
            <RequireGuest>
              <Login />
            </RequireGuest>
          }
        />
      </>,
      ['/login'],
      null,
    );
    expect(screen.getByTestId('content').textContent).toBe('login');
  });
});

describe('guard composition', () => {
  beforeEach(() => tokenStorage.clear());
  afterEach(() => tokenStorage.clear());

  test('outer guard fires first when both would redirect', () => {
    renderWithRoutes(
      <>
        <Route
          path="/admin"
          element={
            <RequireAuth>
              <RequireRole role="ADMIN">
                <Protected />
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route path="/login" element={<Login />} />
        <Route path="/403" element={<Forbidden />} />
      </>,
      ['/admin'],
      null,
    );
    // No token → RequireAuth wins → /login (not /403).
    expect(screen.getByTestId('content').textContent).toBe('login');
  });

  test('renders content only when every guard in the stack passes', () => {
    renderWithRoutes(
      <>
        <Route
          path="/admin"
          element={
            <RequireAuth>
              <RequireRole role="ADMIN">
                <Protected />
              </RequireRole>
            </RequireAuth>
          }
        />
      </>,
      ['/admin'],
      aBundle({ token: makeJwt('ADMIN') }),
    );
    expect(screen.getByTestId('content').textContent).toBe('protected');
  });
});
