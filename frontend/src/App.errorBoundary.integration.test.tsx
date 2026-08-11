import { afterEach, beforeEach, describe, expect, test, vi, type MockInstance } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/shared/auth/AuthContext';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { ErrorBoundary } from '@/shared/layout/ErrorBoundary';

/**
 * Guards the production wiring in `main.tsx`: an intentionally-throwing
 * component rendered through the same provider stack as production still
 * surfaces the ErrorBoundary fallback instead of an empty root element.
 */
function Bomb(): JSX.Element {
  throw new Error('kaboom');
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

let consoleErrorSpy: MockInstance<Parameters<typeof console.error>, void>;

beforeEach(() => {
  tokenStorage.clear();
  consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  tokenStorage.clear();
  consoleErrorSpy.mockRestore();
});

describe('ErrorBoundary at the app root', () => {
  test('renders the minimalist fallback when a child throws through the full provider stack', () => {
    // Mirrors the production wiring shape from `main.tsx`: ErrorBoundary is
    // OUTSIDE the providers so a provider crashing during initial render is
    // still caught. We omit <StrictMode> here because RTL renders in a
    // controlled environment and StrictMode's double-invoke would duplicate
    // the console.error assertion for no test signal.
    render(
      <ErrorBoundary>
        <QueryClientProvider client={freshClient()}>
          <AuthProvider>
            <MemoryRouter>
              <Bomb />
            </MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </ErrorBoundary>,
    );

    expect(
      screen.getByRole('heading', { level: 2, name: /unexpected error/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/please reload the page/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
  });

  test('does not swallow the error silently — console.error is called', () => {
    render(
      <ErrorBoundary>
        <QueryClientProvider client={freshClient()}>
          <AuthProvider>
            <MemoryRouter>
              <Bomb />
            </MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </ErrorBoundary>,
    );

    expect(consoleErrorSpy).toHaveBeenCalled();
  });

  test('renders children unchanged when nothing throws', () => {
    render(
      <ErrorBoundary>
        <QueryClientProvider client={freshClient()}>
          <AuthProvider>
            <MemoryRouter>
              <p data-testid="ok-child">healthy tree</p>
            </MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </ErrorBoundary>,
    );

    expect(screen.getByTestId('ok-child')).toHaveTextContent('healthy tree');
    // Fallback DOM must be absent on the happy path.
    expect(screen.queryByRole('button', { name: /reload/i })).toBeNull();
  });
});
