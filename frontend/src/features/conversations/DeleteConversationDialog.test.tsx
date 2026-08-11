import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { DeleteConversationDialog } from './DeleteConversationDialog';
import type { Conversation } from './schema';

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

function Harness({
  conversation,
  onDeleted,
  onClose,
}: {
  conversation: Conversation;
  onDeleted: (c: Conversation) => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(true);
  return (
    <DeleteConversationDialog
      conversation={conversation}
      open={open}
      onClose={() => {
        setOpen(false);
        onClose?.();
      }}
      onDeleted={onDeleted}
    />
  );
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('DeleteConversationDialog', () => {
  test('renders the cascade warning and the conversation title', () => {
    renderWithProviders(
      <Harness conversation={aConversation({ title: 'Project plan' })} onDeleted={vi.fn()} />,
    );
    expect(screen.getByText(/cannot be undone/i)).toBeInTheDocument();
    expect(screen.getByText('Project plan')).toBeInTheDocument();
  });

  test('title fallback: when title=null, renders chat-<uuid-short>', () => {
    renderWithProviders(
      <Harness conversation={aConversation({ title: null })} onDeleted={vi.fn()} />,
    );
    expect(screen.getByText(`chat-${CONV_ID.slice(0, 8)}`)).toBeInTheDocument();
  });

  test('204 success: onDeleted called and dialog closes', async () => {
    server.use(
      http.delete(
        `${BASE}/conversations/:conversationId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <Harness conversation={aConversation()} onDeleted={onDeleted} onClose={onClose} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });

  test('500: inline alert renders, onDeleted NOT called, dialog stays open', async () => {
    server.use(
      http.delete(`${BASE}/conversations/:conversationId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <Harness conversation={aConversation()} onDeleted={onDeleted} onClose={onClose} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  test('404 NOT_FOUND: inline error renders, onDeleted is NOT called', async () => {
    server.use(
      http.delete(`${BASE}/conversations/:conversationId`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    renderWithProviders(<Harness conversation={aConversation()} onDeleted={onDeleted} />);

    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
