import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { ConversationList } from './ConversationList';
import type { Conversation } from './schema';
import type { Agent } from '@/features/agents/schema';

const BASE = env.VITE_API_BASE_URL;

const CONV_A = '11111111-1111-4111-9111-111111111111';
const CONV_B = '22222222-2222-4222-9222-222222222222';
const CONV_C = '33333333-3333-4333-9333-333333333333';
const AGENT_A = 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa';
const AGENT_B = 'bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: CONV_A,
    agentId: AGENT_A,
    title: 'Brainstorming',
    messageCount: 4,
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

function agentHandlers(agents: Agent[]) {
  const byId = new Map(agents.map((a) => [a.id, a] as const));
  return [
    http.get(`${BASE}/agents`, () =>
      HttpResponse.json({ items: agents, nextCursor: null, pageSize: 20 }),
    ),
    http.get(`${BASE}/agents/:agentId`, ({ params }) => {
      const a = byId.get(params.agentId as string);
      if (!a) {
        return HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      return HttpResponse.json(a);
    }),
  ];
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('ConversationList', () => {
  test('renders loading skeletons before the first page resolves', async () => {
    server.use(
      ...agentHandlers([]),
      http.get(`${BASE}/conversations`, async () => {
        await new Promise((r) => setTimeout(r, 20));
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
    );

    renderWithProviders(
      <ConversationList onSelect={vi.fn()} onNew={vi.fn()} />,
    );
    expect(screen.getByTestId('conversation-list-loading')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/no chats yet/i)).toBeInTheDocument());
  });

  test('empty state renders with "Start a chat" CTA that calls onNew', async () => {
    const onNew = vi.fn();
    server.use(
      ...agentHandlers([]),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderWithProviders(<ConversationList onSelect={vi.fn()} onNew={onNew} />);
    await waitFor(() => expect(screen.getByText(/no chats yet/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /start a chat/i }));
    expect(onNew).toHaveBeenCalledTimes(1);
  });

  test('header "+" button fires onNew', async () => {
    const onNew = vi.fn();
    server.use(
      ...agentHandlers([anAgent()]),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [aConversation()],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ConversationList onSelect={vi.fn()} onNew={onNew} />);
    await waitFor(() => expect(screen.getByText('Brainstorming')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /start a new chat/i }));
    expect(onNew).toHaveBeenCalledTimes(1);
  });

  test('pagination: first page renders, Load more appends the next page', async () => {
    server.use(
      ...agentHandlers([anAgent()]),
      http.get(`${BASE}/conversations`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [aConversation({ id: CONV_A, title: 'first' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [aConversation({ id: CONV_B, title: 'second' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(<ConversationList onSelect={vi.fn()} onNew={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /load more/i }));
    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());
  });

  test('filter narrows by title; Clear restores the full list', async () => {
    server.use(
      ...agentHandlers([anAgent()]),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [
            aConversation({ id: CONV_A, title: 'Alpha' }),
            aConversation({ id: CONV_B, title: 'Beta' }),
            aConversation({ id: CONV_C, title: 'Gamma' }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ConversationList onSelect={vi.fn()} onNew={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText(/search conversations/i), 'bet');
    await waitFor(() => expect(screen.queryByText('Alpha')).not.toBeInTheDocument());
    expect(screen.getByText('Beta')).toBeInTheDocument();
    expect(screen.queryByText('Gamma')).not.toBeInTheDocument();

    // Filtering to nothing shows the no-matches affordance with Clear.
    await userEvent.clear(screen.getByLabelText(/search conversations/i));
    await userEvent.type(screen.getByLabelText(/search conversations/i), 'zzz');
    await waitFor(() =>
      expect(screen.getByTestId('conversation-list-no-matches')).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole('button', { name: /clear/i }));
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());
  });

  test('filter matches by agent name', async () => {
    const agentMap: Record<string, Agent> = {
      [AGENT_A]: anAgent({ id: AGENT_A, name: 'Researcher' }),
      [AGENT_B]: anAgent({ id: AGENT_B, name: 'Reviewer' }),
    };
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: Object.values(agentMap),
          nextCursor: null,
          pageSize: 20,
        }),
      ),
      http.get(`${BASE}/agents/:agentId`, ({ params }) => {
        const a = agentMap[params.agentId as string];
        if (!a) {
          return HttpResponse.json(
            { title: 'Not found', status: 404, code: 'NOT_FOUND' },
            { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json(a);
      }),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [
            aConversation({ id: CONV_A, title: 'one', agentId: AGENT_A }),
            aConversation({ id: CONV_B, title: 'two', agentId: AGENT_B }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ConversationList onSelect={vi.fn()} onNew={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('one')).toBeInTheDocument());
    // Wait for agent names to resolve in the list-level lookup.
    await waitFor(() => expect(screen.getAllByText('Researcher').length).toBeGreaterThan(0));

    await userEvent.type(screen.getByLabelText(/search conversations/i), 'reviewer');
    await waitFor(() => expect(screen.queryByText('one')).not.toBeInTheDocument());
    expect(screen.getByText('two')).toBeInTheDocument();
  });

  test('active highlighting: passing activeConversationId marks the row aria-selected', async () => {
    server.use(
      ...agentHandlers([anAgent()]),
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
    );

    renderWithProviders(
      <ConversationList
        activeConversationId={CONV_B}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );

    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());
    const rows = screen.getAllByRole('option');
    const activeRow = rows.find((r) => r.getAttribute('aria-selected') === 'true');
    expect(activeRow).toBeDefined();
    expect(activeRow?.textContent).toContain('second');
  });

  test('clicking a row fires onSelect with that conversation id', async () => {
    const onSelect = vi.fn();
    server.use(
      ...agentHandlers([anAgent()]),
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
    );

    renderWithProviders(
      <ConversationList onSelect={onSelect} onNew={vi.fn()} />,
    );
    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());
    await userEvent.click(screen.getByText('second'));
    expect(onSelect).toHaveBeenCalledWith(CONV_B);
  });

  test('keyboard nav: ArrowDown moves focus and Enter fires onSelect on focused row', async () => {
    const onSelect = vi.fn();
    server.use(
      ...agentHandlers([anAgent()]),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({
          items: [
            aConversation({ id: CONV_A, title: 'first' }),
            aConversation({ id: CONV_B, title: 'second' }),
            aConversation({ id: CONV_C, title: 'third' }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(
      <ConversationList onSelect={onSelect} onNew={vi.fn()} />,
    );
    await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument());

    const rows = screen.getAllByRole('option');
    rows[0]!.focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(rows[1]);
    await userEvent.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith(CONV_B);

    await userEvent.keyboard('{ArrowUp}');
    expect(document.activeElement).toBe(rows[0]);
  });

  test('first-page 500 surfaces alert + Retry that re-fetches', async () => {
    let calls = 0;
    server.use(
      ...agentHandlers([anAgent()]),
      http.get(`${BASE}/conversations`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({
          items: [aConversation({ id: CONV_A, title: 'recovered' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(
      <ConversationList onSelect={vi.fn()} onNew={vi.fn()} />,
    );
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('recovered')).toBeInTheDocument());
  });
});
