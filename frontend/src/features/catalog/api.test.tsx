import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { invalidateCatalogs, useMcpServers, useTools } from './api';

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

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('useTools', () => {
  test('200 happy path: returns the items array', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json({
          items: [
            { name: 'aws_s3', description: 'List/Get S3 objects.' },
            { name: 'http_fetch', description: 'Fetch a URL.' },
          ],
        }),
      ),
    );
    const { result } = renderHook(() => useTools(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([
      { name: 'aws_s3', description: 'List/Get S3 objects.' },
      { name: 'http_fetch', description: 'Fetch a URL.' },
    ]);
  });

  test('INTERNAL_ERROR 500 surfaces as ApiError', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const { result } = renderHook(() => useTools(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('INTERNAL_ERROR');
    expect(result.current.data).toBeUndefined();
  });

  test('staleTime: Infinity → second hook call hits cache (one network request)', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/tools`, () => {
        calls += 1;
        return HttpResponse.json({ items: [{ name: 'aws_s3', description: 'd' }] });
      }),
    );
    const client = freshClient();
    const { result, rerender } = renderHook(() => useTools(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calls).toBe(1);

    rerender();
    rerender();
    expect(calls).toBe(1);

    // A second consumer in the same client also reads from cache.
    const second = renderHook(() => useTools(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true));
    expect(calls).toBe(1);
  });
});

describe('useMcpServers', () => {
  test('200 happy path: returns the items array', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({
          items: [
            { name: 'filesystem', description: 'Local filesystem MCP.' },
            { name: 'github', description: null },
          ],
        }),
      ),
    );
    const { result } = renderHook(() => useMcpServers(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([
      { name: 'filesystem', description: 'Local filesystem MCP.' },
      { name: 'github', description: null },
    ]);
  });

  test('empty catalog: data is an empty array, no error', async () => {
    server.use(http.get(`${BASE}/mcp-servers`, () => HttpResponse.json({ items: [] })));
    const { result } = renderHook(() => useMcpServers(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
    expect(result.current.error).toBeNull();
  });

  test('staleTime: Infinity → cached across re-renders', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/mcp-servers`, () => {
        calls += 1;
        return HttpResponse.json({ items: [{ name: 'filesystem' }] });
      }),
    );
    const client = freshClient();
    const first = renderHook(() => useMcpServers(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
    expect(calls).toBe(1);

    const second = renderHook(() => useMcpServers(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true));
    expect(calls).toBe(1);
  });
});

describe('invalidateCatalogs', () => {
  test('after invalidation, a subsequent useTools call refetches', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/tools`, () => {
        calls += 1;
        return HttpResponse.json({ items: [{ name: 'aws_s3', description: 'd' }] });
      }),
    );
    const client = freshClient();
    const first = renderHook(() => useTools(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
    expect(calls).toBe(1);

    invalidateCatalogs(client);

    // A fresh consumer triggers the refetch on the invalidated key.
    const second = renderHook(() => useTools(), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(calls).toBe(2));
    expect(second.result.current.isSuccess).toBe(true);
  });
});
