import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { _resetToasts } from '@/shared/ui/Toast';
import { routes } from '@/pages/routes';

/**
 * Guards the wiring of `<OfflineBanner>` into both `AppShell` and `AuthShell`:
 * with `navigator.onLine === false` at initial render, the banner is present
 * on both a logged-out `/login` view and a logged-in home view.
 */

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

function aBundle(): TokenBundle {
  return {
    token: makeJwt(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
  };
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderAt(initialEntries: string[]) {
  const memoryRouter = createMemoryRouter(routes, { initialEntries });
  return render(
    <QueryClientProvider client={freshClient()}>
      <AuthProvider>
        <RouterProvider router={memoryRouter} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

function setNavigatorOnLine(value: boolean): () => void {
  const original = Object.getOwnPropertyDescriptor(
    Object.getPrototypeOf(navigator),
    'onLine',
  );
  Object.defineProperty(navigator, 'onLine', {
    configurable: true,
    get: () => value,
  });
  return () => {
    if (original) {
      Object.defineProperty(Object.getPrototypeOf(navigator), 'onLine', original);
    }
  };
}

let restoreOnline: (() => void) | null = null;

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
  // Default every test to "online" — individual tests opt in to offline.
  restoreOnline = setNavigatorOnLine(true);
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
  restoreOnline?.();
  restoreOnline = null;
});

describe('OfflineBanner mounted in the shells', () => {
  test('AuthShell (`/login`) renders the banner when offline at mount', async () => {
    restoreOnline = setNavigatorOnLine(false);

    renderAt(['/login']);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: /sign in/i })).toBeInTheDocument(),
    );
    expect(screen.getByTestId('offline-banner')).toBeInTheDocument();
  });

  test('AppShell (`/`) renders the banner when offline at mount', async () => {
    restoreOnline = setNavigatorOnLine(false);
    tokenStorage.set(aBundle());

    renderAt(['/']);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /profile menu/i })).toBeInTheDocument(),
    );
    expect(screen.getByTestId('offline-banner')).toBeInTheDocument();
  });

  test('AppShell does NOT render the banner when online', async () => {
    tokenStorage.set(aBundle());

    renderAt(['/']);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /profile menu/i })).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('offline-banner')).toBeNull();
  });
});
