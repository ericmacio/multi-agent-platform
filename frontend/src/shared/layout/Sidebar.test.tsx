import { beforeEach, describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { makeTestQueryClient } from '@/test/render';
import { Sidebar } from './Sidebar';

function base64url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(role: 'ADMIN' | 'STANDARD'): string {
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
function aBundle(role: 'ADMIN' | 'STANDARD'): TokenBundle {
  return {
    token: makeJwt(role),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
  };
}

function renderSidebar(bundle: TokenBundle | null) {
  if (bundle) tokenStorage.set(bundle);
  else tokenStorage.clear();
  return render(
    <QueryClientProvider client={makeTestQueryClient()}>
      <AuthProvider>
        <MemoryRouter>
          <Sidebar />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('Sidebar', () => {
  beforeEach(() => {
    tokenStorage.clear();
    localStorage.clear();
  });

  test('renders the standard navigation group', () => {
    renderSidebar(aBundle('STANDARD'));
    expect(screen.getByRole('link', { name: /agents/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /chat/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /tools/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /mcp servers/i })).toBeInTheDocument();
  });

  test('hides the Admin group for STANDARD users', () => {
    renderSidebar(aBundle('STANDARD'));
    expect(screen.queryByTestId('sidebar-admin-group')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /api keys/i })).not.toBeInTheDocument();
  });

  test('shows the Admin group for ADMIN users', () => {
    renderSidebar(aBundle('ADMIN'));
    expect(screen.getByTestId('sidebar-admin-group')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /users/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /api keys/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /rate limit/i })).toBeInTheDocument();
  });

  test('Admin disclosure toggle persists to localStorage', async () => {
    renderSidebar(aBundle('ADMIN'));
    expect(screen.getByRole('link', { name: /api keys/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /admin/i }));
    expect(screen.queryByRole('link', { name: /api keys/i })).not.toBeInTheDocument();
    expect(localStorage.getItem('mam.sidebar.admin.open')).toBe('closed');
  });

  test('does NOT render the Admin group when there is no principal', () => {
    renderSidebar(null);
    expect(screen.queryByTestId('sidebar-admin-group')).not.toBeInTheDocument();
  });
});
