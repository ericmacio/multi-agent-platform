import { beforeEach, describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, AuthRedirector } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { makeTestQueryClient } from '@/test/render';
import { Topbar } from './Topbar';

function base64url(s: string) {
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
function aBundle(): TokenBundle {
  return {
    token: makeJwt(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
  };
}

function renderTopbar() {
  tokenStorage.set(aBundle());
  return render(
    <QueryClientProvider client={makeTestQueryClient()}>
      <AuthProvider>
        <MemoryRouter>
          <AuthRedirector />
          <Topbar />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('Topbar', () => {
  beforeEach(() => tokenStorage.clear());

  test('renders the profile trigger with the user initial', () => {
    renderTopbar();
    expect(screen.getByRole('button', { name: 'Profile menu' })).toHaveTextContent('A');
  });

  test('opens the profile dropdown and shows the user email + actions', async () => {
    renderTopbar();
    await userEvent.click(screen.getByRole('button', { name: 'Profile menu' }));
    expect(screen.getByText('alice@example.com')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /change password/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /sign out/i })).toBeInTheDocument();
  });

  test('Sign out clears auth state', async () => {
    renderTopbar();
    expect(tokenStorage.get()).not.toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Profile menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: /sign out/i }));
    expect(tokenStorage.get()).toBeNull();
  });
});
