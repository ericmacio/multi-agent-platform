import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import {
  useConversation,
  useConversations,
  useDeleteConversation,
  useMessages,
  useStartConversation,
  useUpdateConversationTitle,
} from './api';
import type { Conversation, Message } from './schema';

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

const AGENT_A = '11111111-aaaa-4aaa-9aaa-aaaaaaaaaaaa';
const CONV_A = '11111111-1111-4111-9111-111111111111';
const CONV_B = '22222222-2222-4222-9222-222222222222';
const CONV_C = '33333333-3333-4333-9333-333333333333';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_A,
    agentId: AGENT_A,
    title: 'My chat',
    messageCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function aMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
    role: 'USER',
    content: 'hi',
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

describe('useConversations', () => {
  test('200 happy path: first page exposed and fetchNextPage drains cursor', async () => {
    server.use(
      http.get(`${BASE}/conversations`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [aConversation({ id: CONV_A })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [aConversation({ id: CONV_B })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    const { result } = renderHook(() => useConversations(), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(result.current.hasNextPage).toBe(false);
  });

  test('agentId filter is propagated as the ?agentId query parameter', async () => {
    let observedAgentId: string | null = null;
    server.use(
      http.get(`${BASE}/conversations`, ({ request }) => {
        observedAgentId = new URL(request.url).searchParams.get('agentId');
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
    );

    const client = freshClient();
    const { result } = renderHook(() => useConversations({ agentId: AGENT_A }), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(observedAgentId).toBe(AGENT_A);

    // The cache is keyed by agentId.
    expect(client.getQueryData(qk.conversations.list(AGENT_A))).toBeDefined();
    expect(client.getQueryData(qk.conversations.list())).toBeUndefined();
  });
});

describe('useConversation', () => {
  test('200 happy path: returns the Conversation', async () => {
    const conv = aConversation();
    server.use(
      http.get(`${BASE}/conversations/:conversationId`, ({ params }) => {
        expect(params.conversationId).toBe(conv.id);
        return HttpResponse.json(conv);
      }),
    );

    const { result } = renderHook(() => useConversation(conv.id), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe(conv.id);
  });

  test('undefined id: query is disabled, no network call fires', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/conversations/:conversationId`, () => {
        calls += 1;
        return HttpResponse.json(aConversation());
      }),
    );

    const { result } = renderHook(() => useConversation(undefined), {
      wrapper: wrapperFor(freshClient()),
    });
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
    expect(result.current.isPending).toBe(true);
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useMessages', () => {
  test('200 happy path: returns the items array', async () => {
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () =>
        HttpResponse.json({
          items: [aMessage({ id: 'm1', content: 'hi' }), aMessage({ id: 'm2', content: 'hey', role: 'ASSISTANT' })],
          nextCursor: null,
          pageSize: 64,
        }),
      ),
    );

    const { result } = renderHook(() => useMessages(CONV_A), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
    expect(result.current.data?.[1]?.role).toBe('ASSISTANT');
  });

  test('requests pageSize=64', async () => {
    let observedPageSize: string | null = null;
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, ({ request }) => {
        observedPageSize = new URL(request.url).searchParams.get('pageSize');
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 });
      }),
    );

    const { result } = renderHook(() => useMessages(CONV_A), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(observedPageSize).toBe('64');
  });

  test('undefined id: query is disabled, no network call fires', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () => {
        calls += 1;
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 });
      }),
    );

    renderHook(() => useMessages(undefined), { wrapper: wrapperFor(freshClient()) });
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
  });
});

describe('useStartConversation', () => {
  test('201 happy path: invalidates the conversations list cache', async () => {
    const conv = aConversation({ id: CONV_B });
    server.use(
      http.post(`${BASE}/conversations`, () => HttpResponse.json(conv, { status: 201 })),
    );

    const client = freshClient();
    client.setQueryData(qk.conversations.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useStartConversation(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ agentId: AGENT_A });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe(conv.id);

    expect(client.getQueryState(qk.conversations.list())?.isInvalidated).toBe(true);
  });
});

describe('useUpdateConversationTitle', () => {
  test('optimistic patch on the list cache then rolls back on 500', async () => {
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.patch(`${BASE}/conversations/:conversationId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    const client = freshClient();
    const original = aConversation({ id: CONV_A, title: 'old' });
    client.setQueryData(qk.conversations.list(), {
      pageParams: [undefined],
      pages: [{ items: [original], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useUpdateConversationTitle(CONV_A), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ title: 'new' });

    // While the request is in flight (handler awaiting `errorReleased`), the
    // optimistic patch is visible.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
        qk.conversations.list(),
      );
      expect(cache?.pages[0]?.items[0]?.title).toBe('new');
    });

    releaseError?.();
    await waitFor(() => expect(result.current.isError).toBe(true));
    // Rollback restored the original title.
    const after = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
      qk.conversations.list(),
    );
    expect(after?.pages[0]?.items[0]?.title).toBe('old');
  });

  test('optimistic patch on the byId cache survives the in-flight window', async () => {
    server.use(
      http.patch(`${BASE}/conversations/:conversationId`, () =>
        HttpResponse.json(aConversation({ id: CONV_A, title: 'new' })),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.conversations.byId(CONV_A), aConversation({ title: 'old' }));

    const { result } = renderHook(() => useUpdateConversationTitle(CONV_A), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ title: 'new' });

    await waitFor(() => {
      expect(client.getQueryData<Conversation>(qk.conversations.byId(CONV_A))?.title).toBe(
        'new',
      );
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // On settle the cache is invalidated for a re-fetch.
    expect(client.getQueryState(qk.conversations.byId(CONV_A))?.isInvalidated).toBe(true);
  });
});

describe('useDeleteConversation', () => {
  test('204 happy path: optimistically removes from the list cache', async () => {
    server.use(
      http.delete(
        `${BASE}/conversations/:conversationId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.conversations.list(), {
      pageParams: [undefined],
      pages: [
        {
          items: [
            aConversation({ id: CONV_A }),
            aConversation({ id: CONV_B }),
            aConversation({ id: CONV_C }),
          ],
          nextCursor: null,
          pageSize: 20,
        },
      ],
    });

    const { result } = renderHook(() => useDeleteConversation(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ conversationId: CONV_B });

    // Optimistic remove lands inside onMutate.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
        qk.conversations.list(),
      );
      expect(cache?.pages[0]?.items.map((c) => c.id)).toEqual([CONV_A, CONV_C]);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryState(qk.conversations.list())?.isInvalidated).toBe(true);
  });

  test('500: optimistic remove visible in-flight, then rollback restores the deleted entry', async () => {
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.delete(`${BASE}/conversations/:conversationId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    const client = freshClient();
    client.setQueryData(qk.conversations.list(), {
      pageParams: [undefined],
      pages: [
        {
          items: [aConversation({ id: CONV_A }), aConversation({ id: CONV_B })],
          nextCursor: null,
          pageSize: 20,
        },
      ],
    });

    const { result } = renderHook(() => useDeleteConversation(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ conversationId: CONV_B });

    // In-flight: optimistic remove.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
        qk.conversations.list(),
      );
      expect(cache?.pages[0]?.items.map((c) => c.id)).toEqual([CONV_A]);
    });

    releaseError?.();
    await waitFor(() => expect(result.current.isError).toBe(true));
    const after = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
      qk.conversations.list(),
    );
    expect(after?.pages[0]?.items.map((c) => c.id)).toEqual([CONV_A, CONV_B]);
  });
});
