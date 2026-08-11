import { describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { ApiError } from '@/shared/api/errors';
import { api, unwrap } from '@/shared/api/client';
import { flattenPages, useCursorInfiniteQuery, type PageEnvelope } from './pagination';

const BASE = 'http://localhost:8080/api/v1';

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: 0, gcTime: 0 },
    },
  });
}

type AgentLite = { id: string; name: string };

async function fetchAgentsPage(cursor?: string): Promise<PageEnvelope<AgentLite>> {
  const result = await api.GET('/agents', { params: { query: { cursor } } });
  const data = unwrap(result);
  // The openapi-generated type widens `items` to unknown[]; the call site
  // narrows it because we know it's an Agent page. Tests use a lite shape.
  return data as unknown as PageEnvelope<AgentLite>;
}

describe('flattenPages', () => {
  test('returns [] for undefined input (first render)', () => {
    expect(flattenPages<unknown>(undefined)).toEqual([]);
  });

  test('concatenates items across pages', () => {
    const data = {
      pages: [
        { items: [1, 2], nextCursor: 'p2', pageSize: 2 },
        { items: [3], nextCursor: null, pageSize: 2 },
      ],
    };
    expect(flattenPages<number>(data)).toEqual([1, 2, 3]);
  });

  test('returns [] when every page is empty', () => {
    const data = {
      pages: [
        { items: [], nextCursor: null, pageSize: 0 },
        { items: [], nextCursor: null, pageSize: 0 },
      ],
    };
    expect(flattenPages<unknown>(data)).toEqual([]);
  });
});

describe('useCursorInfiniteQuery', () => {
  test('walks through three cursor-linked pages', async () => {
    const pages: Record<string, PageEnvelope<AgentLite>> = {
      __first: {
        items: [{ id: 'a1', name: 'A1' }],
        nextCursor: 'p2',
        pageSize: 1,
      },
      p2: {
        items: [{ id: 'a2', name: 'A2' }],
        nextCursor: 'p3',
        pageSize: 1,
      },
      p3: {
        items: [{ id: 'a3', name: 'A3' }],
        nextCursor: null,
        pageSize: 1,
      },
    };
    server.use(
      http.get(`${BASE}/agents`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor') ?? '__first';
        return HttpResponse.json(pages[cursor]);
      }),
    );

    const { result } = renderHook(
      () =>
        useCursorInfiniteQuery<AgentLite>({
          queryKey: ['agents', 'list'],
          fetchPage: fetchAgentsPage,
        }),
      { wrapper: wrapperFor(freshClient()) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(flattenPages(result.current.data)).toEqual([{ id: 'a1', name: 'A1' }]);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages.length).toBe(2));
    expect(flattenPages(result.current.data)).toHaveLength(2);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages.length).toBe(3));
    expect(flattenPages(result.current.data)).toEqual([
      { id: 'a1', name: 'A1' },
      { id: 'a2', name: 'A2' },
      { id: 'a3', name: 'A3' },
    ]);
    expect(result.current.hasNextPage).toBe(false);
  });

  test('surfaces ApiError thrown from fetchPage', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { result } = renderHook(
      () =>
        useCursorInfiniteQuery<AgentLite>({
          queryKey: ['agents', 'list-err'],
          fetchPage: fetchAgentsPage,
        }),
      { wrapper: wrapperFor(freshClient()) },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeInstanceOf(ApiError);
    expect(result.current.error?.code).toBe('INTERNAL_ERROR');
  });
});
