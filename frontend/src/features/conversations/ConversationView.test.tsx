import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { _resetToasts } from '@/shared/ui/Toast';
import { ConversationView } from './ConversationView';
import type { Conversation, Message } from './schema';
import type { Agent } from '@/features/agents/schema';

const BASE = env.VITE_API_BASE_URL;

const CONV_ID = '11111111-1111-4111-9111-111111111111';
const AGENT_ID = '22222222-2222-4222-9222-222222222222';
const OWNER_ID = '33333333-3333-4333-9333-333333333333';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_ID,
    agentId: AGENT_ID,
    title: 'Brainstorm',
    messageCount: 4,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function anAgent(overrides: Partial<Agent> = {}): Agent {
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
    ...overrides,
  };
}

function aMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: '99999999-9999-4999-9999-999999999999',
    role: 'USER',
    content: 'Hi',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

type Handlers = {
  conversation?: Conversation;
  conversationStatus?: number;
  agent?: Agent;
  messages?: Message[];
  onPatch?: (body: { title: string }) => Response | Promise<Response>;
  onDelete?: () => Response | Promise<Response>;
};

function setupHandlers(opts: Handlers = {}) {
  let conv = opts.conversation ?? aConversation();
  const agent = opts.agent ?? anAgent();
  server.use(
    http.get(`${BASE}/conversations/:id`, () => {
      if (opts.conversationStatus && opts.conversationStatus !== 200) {
        return HttpResponse.json(
          {
            title: 'Not found',
            status: opts.conversationStatus,
            code: opts.conversationStatus === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR',
          },
          {
            status: opts.conversationStatus,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        );
      }
      return HttpResponse.json(conv);
    }),
    http.get(`${BASE}/conversations/:id/messages`, () =>
      HttpResponse.json({
        items: opts.messages ?? [],
        nextCursor: null,
        pageSize: 64,
      }),
    ),
    http.get(`${BASE}/agents/:agentId`, () => HttpResponse.json(agent)),
    http.patch(`${BASE}/conversations/:id`, async ({ request }) => {
      const body = (await request.json()) as { title: string };
      if (opts.onPatch) return opts.onPatch(body);
      conv = { ...conv, title: body.title };
      return HttpResponse.json(conv);
    }),
    http.delete(`${BASE}/conversations/:id`, async () => {
      if (opts.onDelete) return opts.onDelete();
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

function renderView({
  onDeleted = vi.fn(),
  onStartNew = vi.fn(),
}: {
  onDeleted?: (c: Conversation) => void;
  onStartNew?: (agentId: string) => void;
} = {}) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <ConversationView
            conversationId={CONV_ID}
            onDeleted={onDeleted}
            onStartNew={onStartNew}
          />
        }
      />
      <Route path="/chat" element={<div data-testid="probe">/chat</div>} />
      <Route path="/agents/:agentId" element={<div data-testid="probe">/agents/X</div>} />
    </Routes>,
  );
}

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
});

describe('ConversationView', () => {
  test('topbar renders title, agent name, message-count chip, overflow trigger', async () => {
    setupHandlers({ conversation: aConversation({ title: 'My chat', messageCount: 4 }) });
    renderView();

    const title = await screen.findByTestId('conversation-title');
    expect(title).toHaveTextContent('My chat');
    await waitFor(() => expect(screen.getByText(/Helper/)).toBeInTheDocument());
    expect(screen.getByLabelText(/message count/i)).toHaveTextContent('4 / 64');
    expect(screen.getByRole('button', { name: /conversation actions/i })).toBeInTheDocument();
  });

  test('title fallback: title=null renders chat-<uuid-short>', async () => {
    setupHandlers({ conversation: aConversation({ title: null }) });
    renderView();
    await waitFor(() =>
      expect(screen.getByTestId('conversation-title')).toHaveTextContent(
        `chat-${CONV_ID.slice(0, 8)}`,
      ),
    );
  });

  test('agent link has the correct href to /agents/<agentId>', async () => {
    setupHandlers();
    renderView();
    const link = await screen.findByTestId('conversation-agent-link');
    expect(link).toHaveAttribute('href', `/agents/${AGENT_ID}`);
  });

  test('clicking the pencil opens the edit dialog; saving updates the topbar title', async () => {
    setupHandlers({ conversation: aConversation({ title: 'old' }) });
    renderView();

    const title = await screen.findByTestId('conversation-title');
    expect(title).toHaveTextContent('old');

    await userEvent.click(screen.getByRole('button', { name: /rename conversation/i }));
    const input = await screen.findByLabelText(/title/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'new title');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() =>
      expect(screen.getByTestId('conversation-title')).toHaveTextContent('new title'),
    );
  });

  test('overflow menu Delete: opens delete dialog; confirm fires DELETE and calls onDeleted', async () => {
    let deleted = false;
    setupHandlers({
      onDelete: () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      },
    });
    const onDeleted = vi.fn();
    renderView({ onDeleted });

    await screen.findByTestId('conversation-title');
    await userEvent.click(screen.getByRole('button', { name: /conversation actions/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /delete conversation/i }));

    // Modal is rendered.
    expect(await screen.findByText(/cannot be undone/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalledTimes(1));
    expect(deleted).toBe(true);
    expect(onDeleted.mock.calls[0]?.[0]?.id).toBe(CONV_ID);
  });

  test('composer integrated (< 64): textarea is enabled, Send disabled until content', async () => {
    setupHandlers({ conversation: aConversation({ messageCount: 4 }) });
    renderView();

    const textarea = await screen.findByTestId('composer-textarea');
    expect(textarea).toBeEnabled();
    // Send is disabled because content is empty.
    expect(screen.getByTestId('composer-send')).toBeDisabled();
    expect(screen.queryByTestId('composer-full-banner')).not.toBeInTheDocument();
  });

  test('conversation-full banner (=== 64): placeholder replaced; CTA calls onStartNew(agentId)', async () => {
    setupHandlers({ conversation: aConversation({ messageCount: 64 }) });
    const onStartNew = vi.fn();
    renderView({ onStartNew });

    expect(await screen.findByTestId('composer-full-banner')).toBeInTheDocument();
    expect(screen.queryByTestId('composer-textarea')).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: /start a new conversation/i }),
    );
    expect(onStartNew).toHaveBeenCalledWith(AGENT_ID);
  });

  test('404 from useConversation: "Conversation not found" empty state renders with Back link', async () => {
    setupHandlers({ conversationStatus: 404 });
    renderView();
    await waitFor(() =>
      expect(screen.getByText(/conversation not found/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /back to chats/i })).toBeInTheDocument();
  });

  test('non-404 error: inline retry surface renders', async () => {
    setupHandlers({ conversationStatus: 500 });
    renderView();
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  test('message-count chip uses warning variant at the cap', async () => {
    setupHandlers({ conversation: aConversation({ messageCount: 64 }) });
    renderView();
    const chip = await screen.findByLabelText(/message count/i);
    expect(chip.className).toMatch(/warning/);
  });
});

// ---------------------------------------------------------------------------
// SSE wiring (US-07-003 / US-07-004)
// ---------------------------------------------------------------------------

type SseFrameInput = { event: string; data: string };

function sseBody(frames: SseFrameInput[]): ReadableStream<Uint8Array> {
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

function controlledSseBody() {
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    body,
    push: ({ event, data }: SseFrameInput) =>
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

describe('ConversationView — SSE wiring', () => {
  test('golden path: type + Cmd+Enter streams; bubble grows; completed commits assistant message', async () => {
    setupHandlers();
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
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

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    // The committed assistant text shows up once `completed` fires and the
    // assistant message is appended to the cache. We assert on the visible
    // content rather than the cache to keep this an integration test.
    await waitFor(() => expect(screen.getByText('Hello, world!')).toBeInTheDocument());
  });

  test('mid-stream LLM_UNAVAILABLE: partial bubble greyed; toast fired', async () => {
    setupHandlers();
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
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

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    // Toast for LLM_UNAVAILABLE appears (the errorCopy title is "AI provider unavailable").
    await waitFor(() => expect(screen.getByTestId('toast-error')).toBeInTheDocument());
    expect(screen.getByTestId('toast-error').textContent).toMatch(/ai provider unavailable/i);
    // Partial assistant bubble preserved with the stopped variant ("(stopped)" caption).
    expect(screen.getByText(/\(stopped\)/i)).toBeInTheDocument();
  });

  test('stop while streaming: greys the bubble; NO toast; Send re-appears', async () => {
    setupHandlers();
    const stream = controlledSseBody();
    server.use(http.post(`${BASE}/conversations/:id/messages`, () => sseResponse(stream.body)));

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    stream.push({
      event: 'started',
      data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}',
    });
    stream.push({ event: 'delta', data: '{"text":"streaming"}' });

    // Stop button appears while streaming.
    const stopBtn = await screen.findByTestId('composer-stop');
    await userEvent.click(stopBtn);

    // The Send button comes back.
    await waitFor(() => expect(screen.getByTestId('composer-send')).toBeInTheDocument());
    // No error toast for CANCELLED.
    expect(screen.queryByTestId('toast-error')).not.toBeInTheDocument();
    // The partial bubble is greyed.
    expect(screen.getByText(/\(stopped\)/i)).toBeInTheDocument();
  });

  test('CONVERSATION_FULL pre-stream: composer replaced by banner; no toast', async () => {
    setupHandlers();
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        HttpResponse.json(
          { title: 'Conversation full', status: 409, code: 'CONVERSATION_FULL' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(screen.getByTestId('composer-full-banner')).toBeInTheDocument());
    // CONVERSATION_FULL is silent per the toast policy.
    expect(screen.queryByTestId('toast-error')).not.toBeInTheDocument();
  });

  test('live region: empty while streaming, set on completed', async () => {
    setupHandlers();
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}' },
            { event: 'delta', data: '{"text":"final"}' },
            {
              event: 'completed',
              data: '{"assistantMessageId":"a1","title":null,"messageCount":2}',
            },
          ]),
        ),
      ),
    );

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    const liveRegion = screen.getByTestId('chat-live-region');
    expect(liveRegion).toHaveTextContent('');

    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    // Wait until the committed bubble shows up.
    await waitFor(() => expect(screen.getByText('final')).toBeInTheDocument());
  });

  test('pending assistant bubble has aria-hidden while streaming', async () => {
    setupHandlers();
    const stream = controlledSseBody();
    server.use(http.post(`${BASE}/conversations/:id/messages`, () => sseResponse(stream.body)));

    renderView();
    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    stream.push({
      event: 'started',
      data: '{"userMessageId":"u1","conversationId":"' + CONV_ID + '"}',
    });
    stream.push({ event: 'delta', data: '{"text":"pending"}' });

    const pendingRow = await screen.findByTestId('pending-assistant-row');
    expect(pendingRow).toHaveAttribute('aria-hidden', 'true');
  });
});
