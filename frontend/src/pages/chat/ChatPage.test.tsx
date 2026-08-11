import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { _resetToasts } from '@/shared/ui/Toast';
import ChatPage from './ChatPage';
import ConversationPage from './ConversationPage';
import type { Conversation } from '@/features/conversations/schema';
import type { Agent } from '@/features/agents/schema';

const BASE = env.VITE_API_BASE_URL;
const CONV_A = '11111111-1111-4111-9111-111111111111';
const CONV_B = '22222222-2222-4222-9222-222222222222';
const AGENT_A = 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa';
const AGENT_B = 'bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_A,
    agentId: AGENT_A,
    title: 'first',
    messageCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: AGENT_A,
    ownerId: 'cafecafe-cafe-4cfe-9cfe-cafecafecafe',
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

function renderAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/chat" element={<ChatPage />}>
        <Route index element={<div data-testid="probe">/chat (index)</div>} />
        <Route path="new" element={<div data-testid="probe">/chat/new</div>} />
        <Route
          path=":conversationId"
          element={<div data-testid="probe">/chat/:id</div>}
        />
      </Route>
    </Routes>,
    { initialEntries: [path] },
  );
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('ChatPage', () => {
  test('at /chat: renders the left-pane conversation list and the index outlet', async () => {
    server.use(
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [aConversation({ title: 'first' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [anAgent()], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(anAgent())),
    );

    renderAt('/chat');

    expect(await screen.findByTestId('conversation-list')).toBeInTheDocument();
    expect(screen.getByTestId('probe')).toHaveTextContent('/chat (index)');
    await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument());
  });

  test('clicking "+" header navigates to /chat/new', async () => {
    server.use(
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );
    renderAt('/chat');

    await waitFor(() => expect(screen.getByText(/no chats yet/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /start a new chat/i }));
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/chat/new'));
  });

  test('clicking a conversation in the left pane navigates to /chat/<id>', async () => {
    server.use(
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [
            aConversation({ id: CONV_A, title: 'first' }),
            aConversation({ id: CONV_B, title: 'second' }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [anAgent()], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(anAgent())),
    );
    renderAt('/chat');

    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());
    await userEvent.click(screen.getByText('second'));
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/chat/:id'));
  });

  test('at /chat?agentId=<id>: the list is scoped (assert the query parameter)', async () => {
    let observedAgentFilter: string | null = null;
    server.use(
      http.get(`${BASE}/conversations`, ({ request }) => {
        observedAgentFilter = new URL(request.url).searchParams.get('agentId');
        return HttpResponse.json({
          items: [aConversation({ agentId: AGENT_B, title: 'scoped' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: [anAgent({ id: AGENT_B, name: 'OtherHelper' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
      http.get(`${BASE}/agents/:id`, () =>
        HttpResponse.json(anAgent({ id: AGENT_B, name: 'OtherHelper' })),
      ),
    );

    renderAt(`/chat?agentId=${AGENT_B}`);

    await waitFor(() => expect(screen.getByText('scoped')).toBeInTheDocument());
    expect(observedAgentFilter).toBe(AGENT_B);
  });

  test('invalid agentId is ignored: list reads unfiltered', async () => {
    let observedAgentFilter: string | null = null;
    server.use(
      http.get(`${BASE}/conversations`, ({ request }) => {
        observedAgentFilter = new URL(request.url).searchParams.get('agentId');
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderAt('/chat?agentId=not-a-uuid');
    await waitFor(() => expect(screen.getByText(/no chats yet/i)).toBeInTheDocument());
    expect(observedAgentFilter).toBeNull();
  });

  test('navigation aborts an in-flight stream (US-07-004)', async () => {
    _resetToasts();
    const c1 = aConversation({ id: CONV_A, title: 'first' });
    const c2 = aConversation({ id: CONV_B, title: 'second' });

    server.use(
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({ items: [c1, c2], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [anAgent({ id: AGENT_A })], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(anAgent({ id: AGENT_A }))),
      http.get(`${BASE}/conversations/:id`, ({ params }) =>
        HttpResponse.json(params.id === CONV_A ? c1 : c2),
      ),
      http.get(`${BASE}/conversations/:id/messages`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 }),
      ),
    );

    let observedAborted = false;
    server.use(
      http.post(`${BASE}/conversations/:id/messages`, async ({ request }) => {
        request.signal.addEventListener('abort', () => {
          observedAborted = true;
        });
        // Never-completing stream so the test can drive cancellation.
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            const enc = new TextEncoder();
            controller.enqueue(
              enc.encode(
                `event: started\ndata: {"userMessageId":"u1","conversationId":"${CONV_A}"}\n\n`,
              ),
            );
          },
        });
        return new HttpResponse(body, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }) as unknown as Response;
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/chat" element={<ChatPage />}>
          <Route index element={<div data-testid="probe">/chat (index)</div>} />
          <Route path=":conversationId" element={<ConversationPage />} />
        </Route>
      </Routes>,
      { initialEntries: [`/chat/${CONV_A}`] },
    );

    const textarea = await screen.findByTestId('composer-textarea');
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');

    // Wait until the stream has started (composer becomes Stop).
    await waitFor(() => expect(screen.getByTestId('composer-stop')).toBeInTheDocument());

    // Click on the second conversation in the left pane.
    await userEvent.click(screen.getByText('second'));

    await waitFor(() => expect(observedAborted).toBe(true));
    // No error toast — CANCELLED is silent.
    expect(screen.queryByTestId('toast-error')).not.toBeInTheDocument();
  });
});
