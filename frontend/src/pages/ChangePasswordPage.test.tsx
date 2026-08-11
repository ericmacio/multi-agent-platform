import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/render';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { useAuth } from '@/shared/auth/AuthContext';
import { _resetToasts } from '@/shared/ui/Toast';
import { ChangePasswordPage } from './ChangePasswordPage';

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
function aBundle(overrides: Partial<TokenBundle> = {}): TokenBundle {
  return {
    token: makeJwt(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
    ...overrides,
  };
}

function LocationProbe(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}
function AuthProbe(): JSX.Element {
  const auth = useAuth();
  return (
    <div data-testid="auth">
      tok={auth.token ?? 'none'}|mcp={String(auth.mustChangePassword)}
    </div>
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

async function submitNewPassword() {
  await userEvent.type(screen.getByLabelText(/^current password$/i), 'old');
  await userEvent.type(screen.getByLabelText(/^new password$/i), 'Abcdefghij!');
  await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Abcdefghij!');
  await userEvent.click(screen.getByRole('button', { name: /change password/i }));
}

describe('ChangePasswordPage (integration)', () => {
  test('forced visit renders the banner', () => {
    renderWithProviders(<ChangePasswordPage />, {
      initialEntries: ['/change-password?reason=forced'],
      initialBundle: aBundle({ mustChangePassword: true }),
    });
    expect(screen.getByTestId('forced-banner')).toBeInTheDocument();
    expect(screen.getByTestId('forced-banner')).toHaveTextContent(/temporary password/i);
  });

  test('self-initiated visit (no ?reason=forced) does NOT render the banner', () => {
    renderWithProviders(<ChangePasswordPage />, {
      initialEntries: ['/change-password'],
      initialBundle: aBundle({ mustChangePassword: false }),
    });
    expect(screen.queryByTestId('forced-banner')).not.toBeInTheDocument();
  });

  test('successful change navigates to the dashboard and flips mustChangePassword false; token unchanged', async () => {
    const bundle = aBundle({ mustChangePassword: true });
    server.use(http.put(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 204 })));

    renderWithProviders(
      <>
        <LocationProbe />
        <AuthProbe />
        <ChangePasswordPage />
      </>,
      {
        initialEntries: ['/change-password?reason=forced'],
        initialBundle: bundle,
      },
    );

    expect(screen.getByTestId('auth')).toHaveTextContent('mcp=true');
    await submitNewPassword();

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/'));
    await waitFor(() =>
      expect(screen.getByTestId('auth')).toHaveTextContent(`tok=${bundle.token}|mcp=false`),
    );
  });

  test('self-initiated success also navigates to the dashboard', async () => {
    server.use(http.put(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 204 })));

    renderWithProviders(
      <>
        <LocationProbe />
        <ChangePasswordPage />
      </>,
      {
        initialEntries: ['/change-password'],
        initialBundle: aBundle({ mustChangePassword: false }),
      },
    );
    await submitNewPassword();
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/'));
  });
});
