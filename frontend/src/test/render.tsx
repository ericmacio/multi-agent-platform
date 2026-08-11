import { render, type RenderOptions, type RenderResult } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ReactElement, ReactNode } from 'react';
import { AuthProvider, AuthRedirector } from '@/shared/auth/AuthContext';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { ToastViewport } from '@/shared/ui/Toast';

export type RenderWithProvidersOptions = Omit<RenderOptions, 'wrapper'> & {
  /**
   * Caller-controlled `QueryClient` for tests that need to seed the cache or
   * assert on its state. Defaults to a fresh per-test client so cache state
   * never leaks across tests.
   */
  queryClient?: QueryClient;
  /**
   * Initial entries for the `MemoryRouter`. Defaults to `['/']`. String paths
   * are sufficient for every v1 test; richer entry objects are not exposed.
   */
  initialEntries?: string[];
  /**
   * Pre-populates `tokenStorage` BEFORE the `AuthProvider` mounts, so the
   * provider's initial state already reflects the bundle (no `act`-and-wait
   * needed). Pass `null` (default) for a logged-out fixture.
   */
  initialBundle?: TokenBundle | null;
};

/**
 * Builds a `QueryClient` configured for tests: no retries (so a failing query
 * fails immediately rather than holding the test hostage), no
 * refetch-on-window-focus, no cache time. Mirrors the prod retry / mutation
 * defaults except for retry count.
 */
export function makeTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: 0, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

/**
 * Wraps the rendered tree in the providers every feature slice eventually
 * needs: `QueryClientProvider` → `AuthProvider` → `MemoryRouter`. The
 * `AuthRedirector` is mounted inside the router so tests can assert on the
 * navigation triggered by `auth:logout` / explicit `signOut`.
 */
export function renderWithProviders(
  ui: ReactElement,
  options: RenderWithProvidersOptions = {},
): RenderResult & { queryClient: QueryClient } {
  const {
    queryClient = makeTestQueryClient(),
    initialEntries = ['/'],
    initialBundle = null,
    ...rest
  } = options;

  if (initialBundle) {
    tokenStorage.set(initialBundle);
  } else {
    tokenStorage.clear();
  }

  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={initialEntries}>
          <AuthRedirector />
          {children}
          <ToastViewport />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
  const result = render(ui, { wrapper: Wrapper, ...rest });
  return { ...result, queryClient };
}
