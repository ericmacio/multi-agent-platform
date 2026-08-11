import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { EditTitleDialog } from './EditTitleDialog';
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

function Harness({ conversation, onClose }: { conversation: Conversation; onClose?: () => void }) {
  const [open, setOpen] = useState(true);
  return (
    <EditTitleDialog
      conversation={conversation}
      open={open}
      onClose={() => {
        setOpen(false);
        onClose?.();
      }}
    />
  );
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('EditTitleDialog', () => {
  test('opens with the current title pre-filled', () => {
    renderWithProviders(<Harness conversation={aConversation({ title: 'My chat' })} />);
    const input = screen.getByLabelText(/title/i) as HTMLInputElement;
    expect(input.value).toBe('My chat');
  });

  test('empty title: Save is disabled', async () => {
    renderWithProviders(<Harness conversation={aConversation({ title: 'old' })} />);
    const input = screen.getByLabelText(/title/i);
    await userEvent.clear(input);
    const save = screen.getByRole('button', { name: /^save$/i });
    expect(save).toBeDisabled();
  });

  test('happy path: 200 closes the dialog', async () => {
    const updated = aConversation({ title: 'new' });
    server.use(http.patch(`${BASE}/conversations/:conversationId`, () => HttpResponse.json(updated)));

    const onClose = vi.fn();
    renderWithProviders(
      <Harness conversation={aConversation({ title: 'old' })} onClose={onClose} />,
    );

    const input = screen.getByLabelText(/title/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'new');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test('500: inline alert renders and dialog stays open', async () => {
    server.use(
      http.patch(`${BASE}/conversations/:conversationId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onClose = vi.fn();
    renderWithProviders(
      <Harness conversation={aConversation({ title: 'old' })} onClose={onClose} />,
    );

    const input = screen.getByLabelText(/title/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'new');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
  });

  test('400 VALIDATION_ERROR with field="title": surfaces under the input', async () => {
    server.use(
      http.patch(`${BASE}/conversations/:conversationId`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'title', message: 'Title is reserved.' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<Harness conversation={aConversation({ title: 'old' })} />);
    const input = screen.getByLabelText(/title/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'new');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(screen.getByText(/title is reserved/i)).toBeInTheDocument());
  });
});
