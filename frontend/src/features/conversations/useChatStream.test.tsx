import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { useChatStream, chatStreamReducer } from './useChatStream';
import type { Conversation, Message } from './schema';
import type { ReactNode } from 'react';

const BASE = env.VITE_API_BASE_URL;
const CONV_ID = '11111111-1111-4111-9111-111111111111';
const AGENT_ID = '22222222-2222-4222-9222-222222222222';
const URL_PATTERN = `${BASE}/conversations/${CONV_ID}/messages`;

type Frame = { event: string; data: string };

function sseBody(frames: Frame[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const f of frames) {
        controller.enqueue(encoder.encode(`event: ${f.event}\ndata: ${f.data}\n\n`));
      }
      controller.close();
    },
  });
}

function controlledSseBody(): {
  body: ReadableStream<Uint8Array>;
  push: (f: Frame) => void;
  close: () => void;
} {
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    body,
    push: ({ event, data }) =>
      controller?.enqueue(encoder.encode(`event: ${event}\ndata: ${data}\n\n`)),
    close: () => controller?.close(),
  };
}

function sseResponse(body: ReadableStream<Uint8Array>): Response {
  return new HttpResponse(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  }) as unknown as Response;
}

function makeClient(): QueryClient {
  // gcTime: Infinity — we mutate the cache manually via `setQueryData` without
  // a `useQuery` observer, so we need to prevent immediate GC of those entries.
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        staleTime: 0,
        gcTime: Infinity,
      },
      mutations: { retry: false },
    },
  });
}

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_ID,
    agentId: AGENT_ID,
    title: null,
    messageCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function seedList(client: QueryClient, conversation: Conversation): void {
  client.setQueryData(qk.conversations.list(), {
    pages: [{ items: [conversation], nextCursor: null, pageSize: 20 }],
    pageParams: [undefined],
  });
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
  vi.restoreAllMocks();
});

describe('chatStreamReducer', () => {
  test('send transitions to sending with user message', () => {
    const msg: Message = {
      id: 'tmp',
      role: 'USER',
      content: 'hi',
      createdAt: '2026-01-01T00:00:00Z',
    };
    const next = chatStreamReducer(
      { phase: 'idle', pendingUserMessage: null, pendingAssistantText: '', error: null },
      { type: 'send', userMessage: msg },
    );
    expect(next.phase).toBe('sending');
    expect(next.pendingUserMessage).toEqual(msg);
  });

  test('frame:delta appends text monotonically', () => {
    let state = chatStreamReducer(
      { phase: 'streaming', pendingUserMessage: null, pendingAssistantText: '', error: null },
      { type: 'frame:delta', text: 'Hello' },
    );
    state = chatStreamReducer(state, { type: 'frame:delta', text: ', world' });
    expect(state.pendingAssistantText).toBe('Hello, world');
  });

  test('frame:error preserves pendingAssistantText', () => {
    const err = new (class extends Error {
      code = 'LLM_UNAVAILABLE';
      status = 502;
    })() as never;
    const next = chatStreamReducer(
      {
        phase: 'streaming',
        pendingUserMessage: null,
        pendingAssistantText: 'partial',
        error: null,
      },
      { type: 'frame:error', error: err },
    );
    expect(next.phase).toBe('error');
    expect(next.pendingAssistantText).toBe('partial');
  });

  test('http:error clears pendingAssistantText', () => {
    const err = new (class extends Error {
      code = 'CONVERSATION_FULL';
      status = 409;
    })() as never;
    const next = chatStreamReducer(
      {
        phase: 'sending',
        pendingUserMessage: null,
        pendingAssistantText: '',
        error: null,
      },
      { type: 'http:error', error: err },
    );
    expect(next.phase).toBe('error');
    expect(next.pendingAssistantText).toBe('');
  });
});

describe('useChatStream', () => {
  test('golden path: messages + list cache are patched on completed', async () => {
    server.use(
      http.post(URL_PATTERN, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}' },
            { event: 'delta', data: '{"text":"Hello"}' },
            { event: 'delta', data: '{"text":", world!"}' },
            {
              event: 'completed',
              data: '{"assistantMessageId":"a1","title":"Hello, world!","messageCount":2}',
            },
          ]),
        ),
      ),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);
    seedList(client, aConversation());

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    await act(async () => {
      await result.current.send('hi');
    });

    await waitFor(() => expect(result.current.phase).toBe('completed'));

    const msgs = client.getQueryData<Message[]>(qk.conversations.messages(CONV_ID))!;
    expect(msgs.find((m) => m.id === 'u1')).toBeTruthy();
    expect(msgs.find((m) => m.id === 'a1')).toBeTruthy();
    expect(msgs.find((m) => m.id === 'a1')?.content).toBe('Hello, world!');

    const listCache = client.getQueryData<{
      pages: { items: Conversation[] }[];
    }>(qk.conversations.list());
    const updated = listCache?.pages[0]?.items[0];
    expect(updated?.title).toBe('Hello, world!');
    expect(updated?.messageCount).toBe(2);
  });

  test('first-turn title patches list cache; second turn with null title does not', async () => {
    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);
    seedList(client, aConversation({ title: 'kept' }));

    let turn = 0;
    server.use(
      http.post(URL_PATTERN, () => {
        turn += 1;
        if (turn === 1) {
          return sseResponse(
            sseBody([
              {
                event: 'started',
                data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}',
              },
              { event: 'delta', data: '{"text":"hi"}' },
              {
                event: 'completed',
                data: '{"assistantMessageId":"a1","title":null,"messageCount":4}',
              },
            ]),
          );
        }
        return sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u2","conversationId":"' + CONV_ID + '"}' },
            { event: 'delta', data: '{"text":"yo"}' },
            {
              event: 'completed',
              data: '{"assistantMessageId":"a2","title":null,"messageCount":6}',
            },
          ]),
        );
      }),
    );

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    await act(async () => {
      await result.current.send('one');
    });
    await waitFor(() => expect(result.current.phase).toBe('completed'));

    let listCache = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
      qk.conversations.list(),
    );
    expect(listCache?.pages[0]?.items[0]?.title).toBe('kept');
    expect(listCache?.pages[0]?.items[0]?.messageCount).toBe(4);

    await act(async () => {
      await result.current.send('two');
    });
    await waitFor(() => expect(result.current.phase).toBe('completed'));

    listCache = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
      qk.conversations.list(),
    );
    expect(listCache?.pages[0]?.items[0]?.title).toBe('kept');
    expect(listCache?.pages[0]?.items[0]?.messageCount).toBe(6);
  });

  test('pre-stream 409 CONVERSATION_FULL: optimistic USER bubble is rolled back', async () => {
    server.use(
      http.post(URL_PATTERN, () =>
        HttpResponse.json(
          { title: 'Conversation full', status: 409, code: 'CONVERSATION_FULL' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    await act(async () => {
      await result.current.send('hi');
    });

    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.error?.code).toBe('CONVERSATION_FULL');
    const msgs = client.getQueryData<Message[]>(qk.conversations.messages(CONV_ID))!;
    expect(msgs).toHaveLength(0);
  });

  test('mid-stream error frame: USER bubble persists, partial text preserved', async () => {
    server.use(
      http.post(URL_PATTERN, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}' },
            { event: 'delta', data: '{"text":"partial"}' },
            {
              event: 'error',
              data: '{"title":"LLM down","status":502,"code":"LLM_UNAVAILABLE"}',
            },
          ]),
        ),
      ),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    await act(async () => {
      await result.current.send('hi');
    });

    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.error?.code).toBe('LLM_UNAVAILABLE');
    expect(result.current.pendingAssistantText).toBe('partial');
    const msgs = client.getQueryData<Message[]>(qk.conversations.messages(CONV_ID))!;
    expect(msgs.find((m) => m.id === 'u1')).toBeTruthy();
  });

  test('stop() mid-stream: phase=error code=CANCELLED, partial text preserved', async () => {
    const stream = controlledSseBody();
    server.use(http.post(URL_PATTERN, () => sseResponse(stream.body)));

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    let pending: Promise<void> | undefined;
    act(() => {
      pending = result.current.send('hi');
    });

    stream.push({ event: 'started', data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}' });
    stream.push({ event: 'delta', data: '{"text":"hello"}' });
    await waitFor(() => expect(result.current.pendingAssistantText).toBe('hello'));

    act(() => {
      result.current.stop();
    });

    await act(async () => {
      await pending;
    });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.error?.code).toBe('CANCELLED');
    expect(result.current.pendingAssistantText).toBe('hello');
  });

  test('re-arm after error wipes pendingAssistantText', async () => {
    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    let call = 0;
    server.use(
      http.post(URL_PATTERN, () => {
        call += 1;
        if (call === 1) {
          return sseResponse(
            sseBody([
              {
                event: 'started',
                data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}',
              },
              { event: 'delta', data: '{"text":"partial"}' },
              {
                event: 'error',
                data: '{"title":"LLM down","status":502,"code":"LLM_UNAVAILABLE"}',
              },
            ]),
          );
        }
        return sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u2","conversationId":"' + CONV_ID + '"}' },
            { event: 'delta', data: '{"text":"ok"}' },
            { event: 'completed', data: '{"assistantMessageId":"a2","title":null,"messageCount":2}' },
          ]),
        );
      }),
    );

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });

    await act(async () => {
      await result.current.send('one');
    });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.pendingAssistantText).toBe('partial');

    await act(async () => {
      await result.current.send('two');
    });
    await waitFor(() => expect(result.current.phase).toBe('completed'));
    // 'send' resets pendingAssistantText; the completed delta is 'ok'.
    expect(result.current.pendingAssistantText).toBe('ok');
  });

  test('HTTP 406: phase=error code=NOT_ACCEPTABLE', async () => {
    server.use(
      http.post(URL_PATTERN, () =>
        HttpResponse.json(
          { title: 'Not acceptable', status: 406, code: 'NOT_ACCEPTABLE' },
          { status: 406, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });
    await act(async () => {
      await result.current.send('hi');
    });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.error?.code).toBe('NOT_ACCEPTABLE');
  });

  test('HTTP 429 with Retry-After: retryAfterSeconds surfaces on the ApiError', async () => {
    server.use(
      http.post(URL_PATTERN, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: {
              'Content-Type': 'application/problem+json',
              'Retry-After': '5',
            },
          },
        ),
      ),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result } = renderHook(() => useChatStream(CONV_ID), { wrapper: wrapper(client) });
    await act(async () => {
      await result.current.send('hi');
    });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.error?.code).toBe('RATE_LIMITED');
    expect(result.current.error?.retryAfterSeconds).toBe(5);
  });

  test('unmount aborts the in-flight controller', async () => {
    const stream = controlledSseBody();
    let observedAborted = false;
    server.use(
      http.post(URL_PATTERN, async ({ request }) => {
        request.signal.addEventListener('abort', () => {
          observedAborted = true;
        });
        return sseResponse(stream.body);
      }),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);

    const { result, unmount } = renderHook(() => useChatStream(CONV_ID), {
      wrapper: wrapper(client),
    });

    act(() => {
      void result.current.send('hi');
    });

    stream.push({ event: 'started', data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}' });
    await waitFor(() => expect(result.current.phase).toBe('streaming'));

    unmount();

    await waitFor(() => expect(observedAborted).toBe(true));
  });

  test('conversationId change aborts the previous controller', async () => {
    const stream = controlledSseBody();
    let observedAborted = false;
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, async ({ request }) => {
        request.signal.addEventListener('abort', () => {
          observedAborted = true;
        });
        return sseResponse(stream.body);
      }),
    );

    const client = makeClient();
    client.setQueryData<Message[]>(qk.conversations.messages(CONV_ID), []);
    const OTHER_CONV = '44444444-4444-4444-9444-444444444444';
    client.setQueryData<Message[]>(qk.conversations.messages(OTHER_CONV), []);

    const { result, rerender } = renderHook(({ id }) => useChatStream(id), {
      wrapper: wrapper(client),
      initialProps: { id: CONV_ID },
    });

    act(() => {
      void result.current.send('hi');
    });
    stream.push({
      event: 'started',
      data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}',
    });
    await waitFor(() => expect(result.current.phase).toBe('streaming'));

    rerender({ id: OTHER_CONV });

    await waitFor(() => expect(observedAborted).toBe(true));
    // Previous-turn state is wiped after the change.
    expect(result.current.phase).toBe('idle');
    expect(result.current.pendingAssistantText).toBe('');
  });
});
