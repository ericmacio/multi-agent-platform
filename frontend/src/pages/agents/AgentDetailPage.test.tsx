import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { _resetToasts } from '@/shared/ui/Toast';
import AgentDetailPage from './AgentDetailPage';
import type { Agent } from '@/features/agents/schema';
import type { Conversation } from '@/features/conversations/schema';

const BASE = env.VITE_API_BASE_URL;

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'cccccccc-cccc-4ccc-9ccc-cccccccccccc',
    agentId: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    title: 'kickoff',
    messageCount: 8,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Researcher',
    description: 'Reads docs.',
    systemPrompt: 'You are a researcher.',
    memorySize: 24,
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

function renderPageAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/agents" element={<div data-testid="probe">/agents</div>} />
      <Route path="/agents/:agentId" element={<AgentDetailPage />} />
      <Route path="/agents/:agentId/edit" element={<div data-testid="probe">/agents/X/edit</div>} />
      <Route path="/chat/new" element={<div data-testid="probe">/chat/new</div>} />
      <Route path="/chat" element={<div data-testid="probe">/chat</div>} />
      <Route path="/chat/:id" element={<div data-testid="probe">/chat/conv</div>} />
    </Routes>,
    { initialEntries: [path] },
  );
}

function recentConversationsHandler(items: Conversation[] = []) {
  return http.get(`${BASE}/conversations`, () =>
    HttpResponse.json({ items, nextCursor: null, pageSize: 5 }),
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

describe('AgentDetailPage', () => {
  test('populated detail: six sections render with the correct values', async () => {
    const agent = anAgent({
      tools: ['aws_s3'],
      enabledMcpServers: ['filesystem'],
      team: [],
    });
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      recentConversationsHandler(),
    );

    renderPageAt(`/agents/${agent.id}`);

    // Identity heading
    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    // Identity FieldRow value (also "Researcher")
    expect(screen.getAllByText('Researcher').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Reads docs.')).toBeInTheDocument();
    // Behavior
    expect(screen.getByText('You are a researcher.')).toBeInTheDocument();
    expect(screen.getByText('24 messages')).toBeInTheDocument();
    // Model: all 4 null → "Using platform default"
    expect(screen.getByText(/using platform default/i)).toBeInTheDocument();
    // Tools
    expect(screen.getByText('aws_s3')).toBeInTheDocument();
    // MCP
    expect(screen.getByText('filesystem')).toBeInTheDocument();
    // Team: empty → "(none)"
    const teamSection = screen.getByText('Team').closest('div');
    expect(teamSection?.textContent).toContain('(none)');
  });

  test('Start chat navigates to /chat/new?agentId=…', async () => {
    const agent = anAgent();
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      recentConversationsHandler(),
    );

    renderPageAt(`/agents/${agent.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await userEvent.click(screen.getByRole('button', { name: /start chat/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/chat/new');
  });

  test('Edit navigates to /agents/<id>/edit', async () => {
    const agent = anAgent();
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      recentConversationsHandler(),
    );

    renderPageAt(`/agents/${agent.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/edit');
  });

  test('Delete confirm + success: toast + navigate to /agents', async () => {
    const agent = anAgent({ name: 'Researcher' });
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      http.delete(`${BASE}/agents/:id`, () => new HttpResponse(null, { status: 204 })),
      recentConversationsHandler(),
    );

    renderPageAt(`/agents/${agent.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await userEvent.type(
      screen.getByLabelText(/type "researcher" to confirm/i),
      'Researcher',
    );
    // Two "Delete" buttons exist now (page CTA + dialog CTA). Pick the dialog one.
    const allDelete = screen.getAllByRole('button', { name: /^delete$/i });
    await userEvent.click(allDelete[allDelete.length - 1]!);

    await waitFor(() =>
      expect(screen.getByTestId('probe')).toHaveTextContent('/agents'),
    );
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/agent deleted/i);
  });

  test('Recent conversations populated: renders rows + "See all" link', async () => {
    const agent = anAgent();
    let observedAgentId: string | null = null;
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      http.get(`${BASE}/conversations`, ({ request }) => {
        observedAgentId = new URL(request.url).searchParams.get('agentId');
        return HttpResponse.json({
          items: [
            aConversation({ id: 'cccccccc-cccc-4ccc-9ccc-cccccccccccc', title: 'first' }),
            aConversation({ id: 'dddddddd-dddd-4ddd-9ddd-dddddddddddd', title: 'second' }),
          ],
          nextCursor: null,
          pageSize: 5,
        });
      }),
    );

    renderPageAt(`/agents/${agent.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument());
    expect(screen.getByText('second')).toBeInTheDocument();
    expect(observedAgentId).toBe(agent.id);

    const seeAll = screen.getByRole('link', { name: /see all/i });
    expect(seeAll).toHaveAttribute('href', `/chat?agentId=${agent.id}`);
  });

  test('Recent conversations empty: CTA "Start a chat with this agent" links to /chat/new?agentId=…', async () => {
    const agent = anAgent();
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      recentConversationsHandler([]),
    );

    renderPageAt(`/agents/${agent.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await waitFor(() =>
      expect(screen.getByText(/no conversations yet/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('link', { name: /start a chat with this agent/i })).toHaveAttribute(
      'href',
      `/chat/new?agentId=${agent.id}`,
    );
  });

  test('Recent conversations error: inline alert with Retry, page is unaffected', async () => {
    const agent = anAgent();
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(agent)),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPageAt(`/agents/${agent.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Researcher' });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
});
