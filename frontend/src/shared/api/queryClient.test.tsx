import { describe, expect, test } from 'vitest';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ApiError } from './errors';
import { queryClient } from './queryClient';

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

/**
 * Build a fresh client that inherits the prod retry predicate but disables
 * the inter-retry delay so tests don't sit waiting for the default backoff.
 */
function testClientWithProdRetry(): QueryClient {
  const defaults = queryClient.getDefaultOptions();
  return new QueryClient({
    defaultOptions: {
      queries: {
        ...defaults.queries,
        retryDelay: 0,
      },
      mutations: { ...defaults.mutations },
    },
  });
}

describe('queryClient retry policy', () => {
  test('singleton exposes the documented defaults', () => {
    const opts = queryClient.getDefaultOptions();
    expect(opts.queries?.staleTime).toBe(30_000);
    expect(opts.queries?.refetchOnWindowFocus).toBe(true);
    expect(opts.mutations?.retry).toBe(false);
  });

  test('does NOT retry on a 4xx ApiError', async () => {
    const client = testClientWithProdRetry();
    let attempts = 0;
    const { result } = renderHook(
      () =>
        useQuery({
          queryKey: ['t-4xx'],
          queryFn: async () => {
            attempts += 1;
            throw new ApiError({ status: 400, code: 'VALIDATION_ERROR', title: 'bad' });
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(attempts).toBe(1);
  });

  test('retries once on a 5xx ApiError', async () => {
    const client = testClientWithProdRetry();
    let attempts = 0;
    const { result } = renderHook(
      () =>
        useQuery({
          queryKey: ['t-5xx'],
          queryFn: async () => {
            attempts += 1;
            throw new ApiError({ status: 502, code: 'LLM_UNAVAILABLE', title: 'upstream' });
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(attempts).toBe(2); // 1 initial + 1 retry
  });

  test('retries once on a generic (non-ApiError) network throw', async () => {
    const client = testClientWithProdRetry();
    let attempts = 0;
    const { result } = renderHook(
      () =>
        useQuery({
          queryKey: ['t-network'],
          queryFn: async () => {
            attempts += 1;
            throw new Error('connection refused');
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(attempts).toBe(2);
  });
});
