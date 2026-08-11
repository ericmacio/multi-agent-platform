import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import { useApiKeys, useCreateApiKey, useUpdateApiKey } from './api';
import type { ApiKey, ApiKeyCreated } from './schema';

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

function anApiKey(overrides: Partial<ApiKey> = {}): ApiKey {
  return {
    clientId: 'cid-1',
    label: 'CI',
    disabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('useApiKeys', () => {
  test('200 happy path: first page exposed and fetchNextPage drains cursor', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [anApiKey({ clientId: 'cid-1' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [anApiKey({ clientId: 'cid-2' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    const { result } = renderHook(() => useApiKeys(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('useCreateApiKey', () => {
  test('201 happy path: resolves with ApiKeyCreated + invalidates list', async () => {
    const created: ApiKeyCreated = {
      clientId: 'cid-new',
      label: 'CI',
      disabled: false,
      createdAt: '2026-01-01T00:00:00Z',
      apiKey: 'sk_live_supersecret_1234567890',
    };
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(created, { status: 201 })),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.apiKeys.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useCreateApiKey(), { wrapper: wrapperFor(client) });
    const returned = await result.current.mutateAsync({ label: 'CI' });
    expect(returned.apiKey).toBe(created.apiKey);
    expect(returned.clientId).toBe(created.clientId);

    await waitFor(() => {
      const state = client.getQueryState(qk.admin.apiKeys.list());
      expect(state?.isInvalidated).toBe(true);
    });
  });

  test('400 VALIDATION_ERROR: error surfaced, cache not invalidated', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'label', message: 'too long' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.apiKeys.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useCreateApiKey(), { wrapper: wrapperFor(client) });
    result.current.mutate({ label: 'x' });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('VALIDATION_ERROR');

    const state = client.getQueryState(qk.admin.apiKeys.list());
    expect(state?.isInvalidated).toBe(false);
  });
});

describe('useUpdateApiKey', () => {
  test('optimistic disabled flip on the list cache; onSettled invalidates', async () => {
    const original = anApiKey({ clientId: 'cid-1', disabled: false });
    server.use(
      http.patch(`${BASE}/admin/api-keys/:clientId`, () =>
        HttpResponse.json({ ...original, disabled: true }),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.apiKeys.list(), {
      pageParams: [undefined],
      pages: [{ items: [original], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useUpdateApiKey(original.clientId), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ disabled: true });

    // Optimistic flip visible in the cache before the response resolves.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: ApiKey[] }[] }>(
        qk.admin.apiKeys.list(),
      );
      expect(cache?.pages[0]?.items[0]?.disabled).toBe(true);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryState(qk.admin.apiKeys.list())?.isInvalidated).toBe(true);
  });

  test('rollback on 500: sibling useApiKeys observes the original disabled', async () => {
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.patch(`${BASE}/admin/api-keys/:clientId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    const client = freshClient();
    const original = anApiKey({ clientId: 'cid-1', disabled: false });
    client.setQueryData(qk.admin.apiKeys.list(), {
      pageParams: [undefined],
      pages: [{ items: [original], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useUpdateApiKey(original.clientId), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ disabled: true });

    // In-flight: optimistic flip is visible.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: ApiKey[] }[] }>(
        qk.admin.apiKeys.list(),
      );
      expect(cache?.pages[0]?.items[0]?.disabled).toBe(true);
    });

    releaseError?.();
    await waitFor(() => expect(result.current.isError).toBe(true));

    // Rollback restored the original disabled value.
    const cache = client.getQueryData<{ pages: { items: ApiKey[] }[] }>(
      qk.admin.apiKeys.list(),
    );
    expect(cache?.pages[0]?.items[0]?.disabled).toBe(false);
  });
});
