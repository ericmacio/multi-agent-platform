import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { makeTestQueryClient, renderWithProviders } from '@/test/render';
import { ConversationListItem } from './ConversationListItem';
import type { Conversation } from './schema';

const BASE = env.VITE_API_BASE_URL;
const AGENT_ID = '11111111-aaaa-4aaa-9aaa-aaaaaaaaaaaa';

function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'cccccccc-cccc-4ccc-9ccc-cccccccccccc',
    agentId: AGENT_ID,
    title: 'Brainstorming',
    messageCount: 4,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function seedAgent(client: ReturnType<typeof makeTestQueryClient>, name: string): void {
  client.setQueryData(qk.agents.byId(AGENT_ID), {
    id: AGENT_ID,
    ownerId: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
    name,
    description: 'desc',
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
  });
}

beforeEach(() => {
  tokenStorage.clear();
  // Catch-all so seeded-cache tests don't surface MSW "no handler" noise when
  // TanStack's background refetch fires after mount.
  server.use(
    http.get(`${BASE}/agents/:agentId`, () =>
      HttpResponse.json(
        { title: 'Not found', status: 404, code: 'NOT_FOUND' },
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
});
afterEach(() => {
  tokenStorage.clear();
});

describe('ConversationListItem', () => {
  test('title fallback: title=null renders chat-<uuid-short>', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation({
          title: null,
          id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
        })}
        active={false}
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    // First 8 chars of the id are shown after the "chat-" prefix.
    expect(screen.getByText('chat-')).toBeInTheDocument();
    expect(screen.getByText('7c7e1a2c')).toBeInTheDocument();
  });

  test('non-null title renders verbatim and no chat- prefix is shown', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation({ title: 'My chat' })}
        active={false}
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    expect(screen.getByText('My chat')).toBeInTheDocument();
    expect(screen.queryByText('chat-')).not.toBeInTheDocument();
  });

  test('message-count chip uses warning tone at messageCount === 64', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation({ messageCount: 64 })}
        active={false}
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    const chip = screen.getByText('64 / 64');
    expect(chip.className).toContain('text-warning');
  });

  test('message-count chip uses neutral tone below cap', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation({ messageCount: 10 })}
        active={false}
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    const chip = screen.getByText('10 / 64');
    expect(chip.className).not.toContain('text-warning');
  });

  test('active=true sets aria-selected and applies the accent border class', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    const row = screen.getByRole('option');
    expect(row).toHaveAttribute('aria-selected', 'true');
    expect(row.className).toContain('border-accent');
  });

  test('active=false has aria-selected=false', () => {
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active={false}
        onClick={vi.fn()}
      />,
      { queryClient: client },
    );
    expect(screen.getByRole('option')).toHaveAttribute('aria-selected', 'false');
  });

  test('click fires onClick', async () => {
    const onClick = vi.fn();
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active={false}
        onClick={onClick}
      />,
      { queryClient: client },
    );
    await userEvent.click(screen.getByRole('option'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  test('Enter on the row fires onClick', async () => {
    const onClick = vi.fn();
    const client = makeTestQueryClient();
    seedAgent(client, 'Helper');
    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active
        onClick={onClick}
      />,
      { queryClient: client },
    );
    const row = screen.getByRole('option');
    row.focus();
    await userEvent.keyboard('{Enter}');
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  test('agent name resolves from useAgent and renders on the secondary line', async () => {
    server.use(
      http.get(`${BASE}/agents/:agentId`, ({ params }) => {
        expect(params.agentId).toBe(AGENT_ID);
        return HttpResponse.json({
          id: AGENT_ID,
          ownerId: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
          name: 'Researcher',
          description: 'desc',
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
        });
      }),
    );

    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active={false}
        onClick={vi.fn()}
      />,
    );
    await waitFor(() => expect(screen.getByText('Researcher')).toBeInTheDocument());
  });

  test('agent fetch error falls back to "Unknown agent"', async () => {
    server.use(
      http.get(`${BASE}/agents/:agentId`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(
      <ConversationListItem
        conversation={aConversation()}
        active={false}
        onClick={vi.fn()}
      />,
    );
    await waitFor(() => expect(screen.getByText('Unknown agent')).toBeInTheDocument());
  });
});
