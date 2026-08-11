import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/render';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { LoginForm, safeNextPath } from './LoginForm';

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

function loginErr(
  status: number,
  body: Record<string, unknown>,
  headers: Record<string, string> = {},
) {
  return http.post(`${BASE}/auth/login`, () =>
    HttpResponse.json(body, {
      status,
      headers: { 'Content-Type': 'application/problem+json', ...headers },
    }),
  );
}

function LocationProbe(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}

beforeEach(() => tokenStorage.clear());
afterEach(() => {
  tokenStorage.clear();
  vi.useRealTimers();
});

describe('safeNextPath', () => {
  test('returns / (dashboard) for null and empty', () => {
    expect(safeNextPath(null)).toBe('/');
    expect(safeNextPath('')).toBe('/');
  });
  test('returns the input for a simple relative path', () => {
    expect(safeNextPath('/agents')).toBe('/agents');
    expect(safeNextPath('/agents?cursor=abc')).toBe('/agents?cursor=abc');
    expect(safeNextPath('/chat/abc')).toBe('/chat/abc');
  });
  test('rejects paths without a leading /', () => {
    expect(safeNextPath('agents')).toBe('/');
    expect(safeNextPath('javascript:alert(1)')).toBe('/');
    expect(safeNextPath('https://evil.com')).toBe('/');
  });
  test('rejects protocol-relative URLs', () => {
    expect(safeNextPath('//evil.com/x')).toBe('/');
    expect(safeNextPath('//evil.com')).toBe('/');
  });
});

describe('LoginForm', () => {
  test('client-side validation fires on empty submit', async () => {
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login'] },
    );
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText(/email is required/i)).toBeInTheDocument();
    expect(screen.getByText(/password is required/i)).toBeInTheDocument();
  });

  test('happy path navigates to the dashboard (/) on success', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(
      () => expect(screen.getByTestId('location').textContent).toBe('/'),
      { timeout: 2000 },
    );
  });

  test('honors a safe ?next= path on success', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login?next=/chat/abc'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await screen.findByText('/chat/abc');
  });

  test('drops a protocol-relative ?next= and falls back to the dashboard', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login?next=//evil.com/x'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/'));
  });

  test('drops an absolute ?next= and falls back to the dashboard', async () => {
    server.use(loginOk(false));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login?next=https://evil.com'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/'));
  });

  test('mustChangePassword=true routes to /change-password?reason=forced and drops ?next=', async () => {
    server.use(loginOk(true));
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login?next=/chat/abc'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'admin@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await screen.findByText('/change-password?reason=forced');
  });

  test('renders a generic alert on 401 INVALID_CREDENTIALS; neither field gets aria-invalid', async () => {
    server.use(
      loginErr(401, { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' }),
    );
    renderWithProviders(
      <>
        <LocationProbe />
        <LoginForm />
      </>,
      { initialEntries: ['/login'] },
    );
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/email or password is incorrect/i);

    expect(screen.getByLabelText(/email/i)).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText(/password/i)).not.toHaveAttribute('aria-invalid');
  });

  test('429 RATE_LIMITED with Retry-After=1 disables submit then re-enables', async () => {
    // Use a 1-second Retry-After so we can wait for the real timer rather
    // than mixing fake timers with MSW (which deadlocks userEvent's internal
    // microtask awaits).
    server.use(
      loginErr(
        429,
        { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
        { 'Retry-After': '1' },
      ),
    );

    renderWithProviders(<LoginForm />, { initialEntries: ['/login'] });

    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/try again in 1s/i);

    const button = screen.getByRole('button', { name: /sign in/i });
    expect(button).toBeDisabled();

    // The countdown decrements over real time; ~1.2s is enough to clear the 1s.
    await waitFor(() => expect(button).not.toBeDisabled(), { timeout: 2000 });
  });

  test('400 VALIDATION_ERROR maps server field errors to local inputs', async () => {
    server.use(
      loginErr(400, {
        title: 'Validation error',
        status: 400,
        code: 'VALIDATION_ERROR',
        errors: [{ field: 'email', message: 'must be a known address' }],
      }),
    );
    renderWithProviders(<LoginForm />, { initialEntries: ['/login'] });
    await userEvent.type(screen.getByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText(/must be a known address/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/email/i)).toHaveAttribute('aria-invalid', 'true');
  });
});
