import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { AgentList } from './AgentList';
import type { Agent } from './schema';

const BASE = env.VITE_API_BASE_URL;

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Alpha',
    description: 'first',
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

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('AgentList', () => {
  test('skeleton on first paint, then renders rows', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: [anAgent({ name: 'Alpha' }), anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Beta' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(
      <AgentList
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(screen.getByTestId('agent-list-loading')).toBeInTheDocument();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();
  });

  test('Load more appends the next page', async () => {
    server.use(
      http.get(`${BASE}/agents`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [anAgent({ name: 'Alpha' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Beta' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(
      <AgentList
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('button', { name: /load more/i }));
    await waitFor(() => expect(screen.getByText('Beta')).toBeInTheDocument());
  });

  test('error state renders alert + Retry, and recovers on success', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/agents`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({
          items: [anAgent({ name: 'Alpha' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(
      <AgentList
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());
  });

  test('returns null when the flattened list is empty (page owns the empty state)', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    const { container } = renderWithProviders(
      <AgentList
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    // The list returns null on empty; the rendered container only has the
    // outermost wrapper from renderWithProviders.
    await waitFor(() => expect(container.querySelector('[data-testid="agent-list-loading"]')).toBeNull());
    // No "Load more" or alert is present either.
    expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
