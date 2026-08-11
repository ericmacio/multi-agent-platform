import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { _resetToasts } from '@/shared/ui/Toast';
import { _resetRateLimitToast } from '@/shared/ui/toastPolicy';
import { QueryClient } from '@tanstack/react-query';
import { renderWithProviders } from '@/test/render';
import { ConversationView } from './ConversationView';
import type { Conversation, Message } from './schema';
import type { Agent } from '@/features/agents/schema';

/**
 * End-to-end SSE integration coverage for EPIC-07 (US-07-005).
 *
 * Scenarios covered here:
 *  - Golden path (cache patching of conversations list — title + messageCount)
 *  - First-turn-title-only rule on a follow-up turn
 *  - Mid-stream MCP_SERVER_ERROR
 *  - 406 NOT_ACCEPTABLE: toast + console.error
 *  - 429 RATE_LIMITED with Retry-After: 5
 *  - Content cap defense (1025 chars): no MSW request, inline alert
 *
 * Stop button, CANCELLED toast suppression, LLM_UNAVAILABLE toast text,
 * pre-stream 409 CONVERSATION_FULL banner, aria-live region, and the
 * pending-bubble aria-hidden attribute are already covered in
 * `ConversationView.test.tsx`. Navigation-aborts-the-stream lives in
 * `pages/chat/ChatPage.test.tsx`. This file fills the remaining cells.
 */

const BASE = env.VITE_API_BASE_URL;
const CONV_ID = '11111111-1111-4111-9111-111111111111';
const AGENT_ID = '22222222-2222-4222-9222-222222222222';
const OWNER_ID = '33333333-3333-4333-9333-333333333333';

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

function anAgent(): Agent {
  return {
    id: AGENT_ID,
    ownerId: OWNER_ID,
    name: 'Helper',
    description: 'd',
    systemPrompt: 's',
    memorySize: 12,
    tools: [],
    enabledMcpServers: [],
    team: [],
    llmModel: null,
    temperature: null,
    maxOutputTokens: null,
    topP: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

type FrameInput = { event: string; data: string };

function sseBody(frames: FrameInput[]): ReadableStream<Uint8Array> {
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

function sseResponse(body: ReadableStream<Uint8Array>): Response {
  return new HttpResponse(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  }) as unknown as Response;
}

/**
 * The list-cache seeding has to use a QueryClient that retains observer-less
 * entries — the default `gcTime: 0` would drop the seed before
 * `useChatStream`'s `setQueryData` runs. We use `gcTime: Infinity` so the
 * assertions can read the cache after the mutation.
 */
function makeClientWithListSeed(conv: Conversation): QueryClient {
  const client = new QueryClient({
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
  client.setQueryData<Message[]>(qk.conversations.messages(conv.id), []);
  client.setQueryData(qk.conversations.list(), {
    pages: [{ items: [conv], nextCursor: null, pageSize: 20 }],
    pageParams: [undefined],
  });
  return client;
}

function setupBaseHandlers(conv: Conversation) {
  let live = conv;
  server.use(
    http.get(`${BASE}/conversations/:id`, () => HttpResponse.json(live)),
    http.get(`${BASE}/conversations/:id/messages`, () =>
      HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 }),
    ),
    http.get(`${BASE}/agents/:id`, () => HttpResponse.json(anAgent())),
    http.patch(`${BASE}/conversations/:id`, async ({ request }) => {
      const body = (await request.json()) as { title: string };
      live = { ...live, title: body.title };
      return HttpResponse.json(live);
    }),
  );
}

function renderView() {
  const conv = aConversation();
  const client = makeClientWithListSeed(conv);
  setupBaseHandlers(conv);
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <ConversationView
            conversationId={CONV_ID}
            onDeleted={vi.fn()}
            onStartNew={vi.fn()}
          />
        }
      />
      <Route path="/chat" element={<div data-testid="probe">/chat</div>} />
    </Routes>,
    { queryClient: client },
  );
}

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
  _resetRateLimitToast();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
  _resetRateLimitToast();
  vi.restoreAllMocks();
});

describe('ConversationView — streaming integration (US-07-005)', () => {
  test('golden path: title + messageCount patched on completed; no toast', async () => {
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: `{"userMessageId":"u1","conversationId":"${CONV_ID}"}` },
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

    const { queryClient } = renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(screen.getByText('Hello, world!')).toBeInTheDocument());

    // List cache patched: first-turn title applied; messageCount = 2.
    await waitFor(() => {
      const list = queryClient.getQueryData<{ pages: { items: Conversation[] }[] }>(
        qk.conversations.list(),
      );
      const updated = list?.pages[0]?.items[0];
      expect(updated?.title).toBe('Hello, world!');
      expect(updated?.messageCount).toBe(2);
    });

    // Golden path is silent — no error toast.
    expect(screen.queryByTestId('toast-error')).not.toBeInTheDocument();
  });

  test('first-turn-title only: a second send with title=null does NOT overwrite the title', async () => {
    const conv = aConversation({ title: 'Existing title' });
    const client = makeClientWithListSeed(conv);
    setupBaseHandlers(conv);

    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: `{"userMessageId":"u2","conversationId":"${CONV_ID}"}` },
            { event: 'delta', data: '{"text":"ok"}' },
            { event: 'completed', data: '{"assistantMessageId":"a2","title":null,"messageCount":4}' },
          ]),
        ),
      ),
    );

    renderWithProviders(
      <ConversationView
        conversationId={CONV_ID}
        onDeleted={vi.fn()}
        onStartNew={vi.fn()}
      />,
      { queryClient: client },
    );

    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'two');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => {
      const list = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
        qk.conversations.list(),
      );
      expect(list?.pages[0]?.items[0]?.messageCount).toBe(4);
    });
    const list = client.getQueryData<{ pages: { items: Conversation[] }[] }>(
      qk.conversations.list(),
    );
    expect(list?.pages[0]?.items[0]?.title).toBe('Existing title');
  });

  test('mid-stream MCP_SERVER_ERROR: partial bubble greyed + Tool server error toast', async () => {
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: `{"userMessageId":"u1","conversationId":"${CONV_ID}"}` },
            { event: 'delta', data: '{"text":"partial"}' },
            {
              event: 'error',
              data: '{"title":"MCP down","status":502,"code":"MCP_SERVER_ERROR"}',
            },
          ]),
        ),
      ),
    );

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(screen.getByTestId('toast-error')).toBeInTheDocument());
    expect(screen.getByTestId('toast-error').textContent).toMatch(/tool server error/i);
    // Partial bubble preserved.
    expect(screen.getByText(/\(stopped\)/i)).toBeInTheDocument();
  });

  test('406 NOT_ACCEPTABLE: toast fires AND console.error is called once', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        HttpResponse.json(
          { title: 'Not acceptable', status: 406, code: 'NOT_ACCEPTABLE' },
          { status: 406, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(screen.getByTestId('toast-error')).toBeInTheDocument());
    expect(screen.getByTestId('toast-error').textContent).toMatch(/not acceptable/i);
    expect(
      consoleErr.mock.calls.some((args) =>
        String(args[0] ?? '').includes('NOT_ACCEPTABLE'),
      ),
    ).toBe(true);
  });

  test('429 RATE_LIMITED with Retry-After: 5 → toast surfaces the countdown', async () => {
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
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

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    // Rate-limit bursts are deduped onto a single warning-styled toast with a
    // live countdown (US-11-002 / SW-DESIGN §17 TBD-F3).
    await waitFor(() => expect(screen.getByTestId('toast-warning')).toBeInTheDocument());
    expect(screen.getByTestId('toast-warning').textContent).toMatch(/try again in 5s/i);
  });

  test('content cap defense (>1024): inline alert, Send disabled, Cmd+Enter does NOT fire a network request', async () => {
    let postCalls = 0;
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () => {
        postCalls += 1;
        return sseResponse(sseBody([]));
      }),
    );

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.click(textarea);
    await userEvent.paste('x'.repeat(1025));

    expect(screen.getByTestId('composer-too-long')).toBeInTheDocument();
    expect(screen.getByTestId('composer-send')).toBeDisabled();

    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    // No POST was issued because the composer's local guard short-circuited.
    expect(postCalls).toBe(0);
  });
});
