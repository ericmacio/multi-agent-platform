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
import ConversationPage from './ConversationPage';
import type { Conversation } from '@/features/conversations/schema';
import type { Agent } from '@/features/agents/schema';

const BASE = env.VITE_API_BASE_URL;
const CONV_ID = '11111111-1111-4111-9111-111111111111';
const AGENT_ID = '22222222-2222-4222-9222-222222222222';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_ID,
    agentId: AGENT_ID,
    title: 'My chat',
    messageCount: 4,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function anAgent(): Agent {
  return {
    id: AGENT_ID,
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
  };
}

function renderAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/chat/:conversationId" element={<ConversationPage />} />
      <Route path="/chat" element={<div data-testid="probe">/chat</div>} />
      <Route path="/chat/new" element={<div data-testid="probe">/chat/new</div>} />
      <Route path="/agents/:agentId" element={<div data-testid="probe">/agents/X</div>} />
    </Routes>,
    { initialEntries: [path] },
  );
}

function defaultHandlers(initial: Conversation = aConversation()) {
  let conversation = initial;
  return [
    http.get(`${BASE}/conversations/:id`, () => HttpResponse.json(conversation)),
    http.get(`${BASE}/conversations/:id/messages`, () =>
      HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 }),
    ),
    http.get(`${BASE}/agents/:id`, () => HttpResponse.json(anAgent())),
    http.patch(`${BASE}/conversations/:id`, async ({ request }) => {
      const body = (await request.json()) as { title: string };
      conversation = { ...conversation, title: body.title };
      return HttpResponse.json(conversation);
    }),
  ];
}

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
});

describe('ConversationPage', () => {
  test('mount at /chat/<id>: topbar shows title; message list renders', async () => {
    server.use(...defaultHandlers());
    renderAt(`/chat/${CONV_ID}`);
    const title = await screen.findByTestId('conversation-title');
    expect(title).toHaveTextContent('My chat');
  });

  test('Delete confirm fires DELETE; on 204 navigates to /chat', async () => {
    server.use(
      ...defaultHandlers(),
      http.delete(`${BASE}/conversations/:id`, () => new HttpResponse(null, { status: 204 })),
    );
    renderAt(`/chat/${CONV_ID}`);

    await screen.findByTestId('conversation-title');
    await userEvent.click(screen.getByRole('button', { name: /conversation actions/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /delete conversation/i }));
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/chat'));
  });

  test('Conversation full: "Start a new conversation" navigates to /chat/new?agentId=…', async () => {
    server.use(...defaultHandlers(aConversation({ messageCount: 64 })));
    renderAt(`/chat/${CONV_ID}`);

    await screen.findByTestId('composer-full-banner');
    await userEvent.click(
      screen.getByRole('button', { name: /start a new conversation/i }),
    );
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/chat/new'));
  });

  test('404 conversation: empty state with Back to chats', async () => {
    server.use(
      http.get(`${BASE}/conversations/:id`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
      http.get(`${BASE}/conversations/:id/messages`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 }),
      ),
    );
    renderAt(`/chat/${CONV_ID}`);
    await waitFor(() =>
      expect(screen.getByText(/conversation not found/i)).toBeInTheDocument(),
    );
  });

  test('Rename: pencil → save propagates to topbar title', async () => {
    server.use(...defaultHandlers());
    renderAt(`/chat/${CONV_ID}`);

    const title = await screen.findByTestId('conversation-title');
    expect(title).toHaveTextContent('My chat');

    await userEvent.click(screen.getByRole('button', { name: /rename conversation/i }));
    const input = await screen.findByLabelText(/title/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'Renamed');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() =>
      expect(screen.getByTestId('conversation-title')).toHaveTextContent('Renamed'),
    );
  });
});
