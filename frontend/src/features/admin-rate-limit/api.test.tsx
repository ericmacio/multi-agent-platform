import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import { useRateLimitConfig, useUpdateRateLimitConfig } from './api';
import type { RateLimitConfig } from './schema';

const BASE = env.VITE_API_BASE_URL;

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function aConfig(overrides: Partial<RateLimitConfig> = {}): RateLimitConfig {
  return {
    perMinute: 60,
    perHour: 3600,
    updatedAt: '2026-01-01T00:00:00Z',
    updatedBy: 'admin-uuid-1',
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('useRateLimitConfig', () => {
  test('200 happy path: exposes the full RateLimitConfig payload', async () => {
    const cfg = aConfig();
    server.use(http.get(`${BASE}/admin/rate-limit`, () => HttpResponse.json(cfg)));

    const { result } = renderHook(() => useRateLimitConfig(), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.perMinute).toBe(60);
    expect(result.current.data?.perHour).toBe(3600);
    expect(result.current.data?.updatedAt).toBe('2026-01-01T00:00:00Z');
    expect(result.current.data?.updatedBy).toBe('admin-uuid-1');
  });

  test('500 error: surfaces ApiError with code=INTERNAL_ERROR for retry rendering', async () => {
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { result } = renderHook(() => useRateLimitConfig(), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('INTERNAL_ERROR');
  });
});

describe('useUpdateRateLimitConfig', () => {
  test('200 happy path: resolves with the updated config and invalidates the cache', async () => {
    const updated = aConfig({ perMinute: 120, updatedAt: '2026-02-01T00:00:00Z' });
    server.use(http.put(`${BASE}/admin/rate-limit`, () => HttpResponse.json(updated)));

    const client = freshClient();
    client.setQueryData<RateLimitConfig>(qk.admin.rateLimit(), aConfig());

    const { result } = renderHook(() => useUpdateRateLimitConfig(), {
      wrapper: wrapperFor(client),
    });
    const returned = await result.current.mutateAsync({ perMinute: 120, perHour: 3600 });
    expect(returned.perMinute).toBe(120);

    await waitFor(() => {
      const state = client.getQueryState(qk.admin.rateLimit());
      expect(state?.isInvalidated).toBe(true);
    });
  });

  test('400 VALIDATION_ERROR: surfaces error, cache is NOT invalidated', async () => {
    server.use(
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'perMinute', message: 'server rule' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = freshClient();
    client.setQueryData<RateLimitConfig>(qk.admin.rateLimit(), aConfig());

    const { result } = renderHook(() => useUpdateRateLimitConfig(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ perMinute: 60, perHour: 3600 });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('VALIDATION_ERROR');

    const state = client.getQueryState(qk.admin.rateLimit());
    expect(state?.isInvalidated).toBe(false);
  });

  test('429 RATE_LIMITED: surfaces error with code=RATE_LIMITED and Retry-After', async () => {
    server.use(
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' },
          },
        ),
      ),
    );

    const { result } = renderHook(() => useUpdateRateLimitConfig(), {
      wrapper: wrapperFor(freshClient()),
    });
    result.current.mutate({ perMinute: 60, perHour: 3600 });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('RATE_LIMITED');
    expect(result.current.error?.retryAfterSeconds).toBe(5);
  });
});
