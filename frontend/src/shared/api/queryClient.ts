import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './errors';

/**
 * Singleton TanStack Query client. Per-feature stale-time overrides land on
 * the call site (`useQuery({ ..., staleTime: Infinity })` for catalogs;
 * `staleTime: 0` for the conversations list); this file only sets defaults.
 *
 * Retry policy:
 * - Queries retry once on 5xx / network failures.
 * - Queries do NOT retry on 4xx — validation, auth, 404 are not transient.
 * - Mutations never retry — the user explicitly invokes them.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: true,
      retry: (failureCount, error) => {
        if (failureCount >= 1) return false;
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return true;
      },
    },
    mutations: {
      retry: false,
    },
  },
});
