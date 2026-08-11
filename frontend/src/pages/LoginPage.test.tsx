import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/render';
import { expectNoA11yViolations } from '@/test/axe';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { useAuth } from '@/shared/auth/AuthContext';
import { LoginPage } from './LoginPage';

const BASE = 'http://localhost:8080/api/v1';

function base64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(): string {
  return [
    base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64url(
      JSON.stringify({
        sub: 'alice@example.com',
        role: 'STANDARD',
        iat: Math.floor(Date.now() / 1000),
        jti: 'j1',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ),
    'sig',
  ].join('.');
}

function loginOk(mustChangePassword = false) {
  return http.post(`${BASE}/auth/login`, () =>
    HttpResponse.json({
      token: makeJwt(),
      tokenType: 'Bearer' as const,
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      mustChangePassword,
    }),
  );
}

function LocationProbe(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}

function AuthProbe(): JSX.Element {
  const auth = useAuth();
  return (
    <div data-testid="auth">
      {auth.token ? 'authed' : 'anon'}|mcp={String(auth.mustChangePassword)}
    </div>
  );
}

beforeEach(() => tokenStorage.clear());
afterEach(() => tokenStorage.clear());

async function submitLogin() {
  await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'pw');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('LoginPage (integration)', () => {
  test('renders the heading and form', () => {
    renderWithProviders(<LoginPage />, { initialEntries: ['/login'] });
    expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  });

  test('a11y: initial paint has no violations', async () => {
    const { container } = renderWithProviders(<LoginPage />, {
      initialEntries: ['/login'],
    });
    await expectNoA11yViolations(container);
  });

  test('happy path: login → /agents', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginPage />
      </>,
      { initialEntries: ['/login'] },
    );
    await submitLogin();
    await screen.findByText('/agents');
  });

  test('forced change: login with mustChangePassword=true → /change-password?reason=forced', async () => {
    server.use(loginOk(true));
    renderWithProviders(
      <>
        <LocationProbe />
        <AuthProbe />
        <LoginPage />
      </>,
      { initialEntries: ['/login'] },
    );
    await submitLogin();
    await screen.findByText('/change-password?reason=forced');
    expect(screen.getByTestId('auth')).toHaveTextContent('authed|mcp=true');
  });

  test('?next= preserved on happy path', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginPage />
      </>,
      { initialEntries: ['/login?next=/chat/abc'] },
    );
    await submitLogin();
    await screen.findByText('/chat/abc');
  });

  test('?next= dropped on forced password change', async () => {
    server.use(loginOk(true));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginPage />
      </>,
      { initialEntries: ['/login?next=/chat/abc'] },
    );
    await submitLogin();
    await screen.findByText('/change-password?reason=forced');
  });

  test('401 INVALID_CREDENTIALS: generic alert; neither field has aria-invalid', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    renderWithProviders(<LoginPage />, { initialEntries: ['/login'] });
    await submitLogin();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/email or password is incorrect/i);
    expect(screen.getByLabelText(/email/i)).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText(/password/i)).not.toHaveAttribute('aria-invalid');
  });

  test('429 RATE_LIMITED renders countdown and re-enables after the wait', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1' },
          },
        ),
      ),
    );
    renderWithProviders(<LoginPage />, { initialEntries: ['/login'] });
    await submitLogin();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/try again in 1s/i);

    const button = screen.getByRole('button', { name: /sign in/i });
    expect(button).toBeDisabled();
    await waitFor(() => expect(button).not.toBeDisabled(), { timeout: 2000 });
  });
});
