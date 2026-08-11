import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import ChatNewPage from './ChatNewPage';
import type { Agent } from '@/features/agents/schema';
import type { Conversation } from '@/features/conversations/schema';

const BASE = env.VITE_API_BASE_URL;

const AGENT_A = 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa';
const AGENT_B = 'bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb';
const AGENT_C = 'cccccccc-cccc-4ccc-9ccc-cccccccccccc';
const NEW_CONV = 'eeeeeeee-eeee-4eee-9eee-eeeeeeeeeeee';

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: AGENT_A,
    ownerId: 'cafecafe-cafe-4cfe-9cfe-cafecafecafe',
    name: 'Researcher',
    description: 'Reads docs.',
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

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: NEW_CONV,
    agentId: AGENT_A,
    title: null,
    messageCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function renderAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/chat/new" element={<ChatNewPage />} />
      <Route path="/chat" element={<div data-testid="probe">/chat</div>} />
      <Route path="/chat/:id" element={<div data-testid="probe">/chat/conv</div>} />
      <Route path="/agents/new" element={<div data-testid="probe">/agents/new</div>} />
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

describe('ChatNewPage', () => {
  test('manual picker: lists agents; clicking Start chat POSTs and navigates', async () => {
    const observed: { agentId?: string } = {};
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: [
            anAgent({ id: AGENT_A, name: 'A' }),
            anAgent({ id: AGENT_B, name: 'B' }),
            anAgent({ id: AGENT_C, name: 'C' }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
      http.post(`${BASE}/conversations`, async ({ request }) => {
        const body = (await request.json()) as { agentId: string };
        observed.agentId = body.agentId;
        return HttpResponse.json(aConversation({ agentId: body.agentId }), { status: 201 });
      }),
    );

    renderAt('/chat/new');

    await waitFor(() => expect(screen.getByText('A')).toBeInTheDocument());
    expect(screen.getByText('B')).toBeInTheDocument();
    expect(screen.getByText('C')).toBeInTheDocument();

    // The agent rows expose their primary CTA. Find the row containing "B" and click its button.
    const buttons = screen.getAllByRole('button', { name: /start chat/i });
    // The rows are in order A, B, C — pick the second.
    await userEvent.click(buttons[1]!);

    await waitFor(() =>
      expect(screen.getByTestId('probe')).toHaveTextContent('/chat/conv'),
    );
    expect(observed.agentId).toBe(AGENT_B);
  });

  test('?agentId=<uuid> valid: auto-fires POST and navigates to /chat/<newId>', async () => {
    let calls = 0;
    server.use(
      http.post(`${BASE}/conversations`, async ({ request }) => {
        calls += 1;
        const body = (await request.json()) as { agentId: string };
        return HttpResponse.json(aConversation({ agentId: body.agentId }), { status: 201 });
      }),
    );

    renderAt(`/chat/new?agentId=${AGENT_A}`);
    expect(screen.getByText(/starting a new conversation/i)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('probe')).toHaveTextContent('/chat/conv'),
    );
    expect(calls).toBe(1);
  });

  test('?agentId=<invalid>: picker renders normally and POST is NOT fired', async () => {
    let posted = false;
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [anAgent()], nextCursor: null, pageSize: 20 }),
      ),
      http.post(`${BASE}/conversations`, () => {
        posted = true;
        return HttpResponse.json(aConversation(), { status: 201 });
      }),
    );
    renderAt('/chat/new?agentId=not-a-uuid');

    await waitFor(() => expect(screen.getByText(/start a new chat/i)).toBeInTheDocument());
    expect(posted).toBe(false);
  });

  test('empty agents: empty state with "Create your first agent" CTA navigates to /agents/new', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );
    renderAt('/chat/new');

    await waitFor(() =>
      expect(screen.getByText(/you don't have any agents yet/i)).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole('button', { name: /create your first agent/i }));
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/agents/new'));
  });

  test('?agentId=<uuid> with 404 POST: error surface with Back to chats link', async () => {
    server.use(
      http.post(`${BASE}/conversations`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    renderAt(`/chat/new?agentId=${AGENT_A}`);
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /back to chats/i })).toHaveAttribute('href', '/chat');
  });

  test('search filters the agent list', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: [
            anAgent({ id: AGENT_A, name: 'Apple' }),
            anAgent({ id: AGENT_B, name: 'Banana' }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderAt('/chat/new');
    await waitFor(() => expect(screen.getByText('Apple')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText(/search agents/i), 'banana');
    await waitFor(() => expect(screen.queryByText('Apple')).not.toBeInTheDocument());
    expect(screen.getByText('Banana')).toBeInTheDocument();
  });
});
