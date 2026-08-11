import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { DeleteAgentDialog } from './DeleteAgentDialog';
import type { Agent } from './schema';

const BASE = env.VITE_API_BASE_URL;

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Researcher',
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

function Harness({
  agent,
  onDeleted,
  onClose,
}: {
  agent: Agent;
  onDeleted: (a: Agent) => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(true);
  return (
    <DeleteAgentDialog
      agent={agent}
      open={open}
      onClose={() => {
        setOpen(false);
        onClose?.();
      }}
      onDeleted={(a) => {
        onDeleted(a);
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

describe('DeleteAgentDialog', () => {
  test('Delete button is disabled until the agent name is typed exactly', async () => {
    const agent = anAgent({ name: 'Researcher' });
    renderWithProviders(<Harness agent={agent} onDeleted={vi.fn()} />);

    const deleteBtn = screen.getByRole('button', { name: /^delete$/i });
    expect(deleteBtn).toBeDisabled();

    const input = screen.getByLabelText(/type "researcher" to confirm/i);
    await userEvent.type(input, 'Research');
    expect(deleteBtn).toBeDisabled();

    await userEvent.type(input, 'er');
    expect(deleteBtn).toBeEnabled();

    // Mismatch resets disabled state.
    await userEvent.type(input, 'x');
    expect(deleteBtn).toBeDisabled();
  });

  test('204 success: onDeleted is called and dialog closes', async () => {
    const agent = anAgent();
    server.use(
      http.delete(`${BASE}/agents/:agentId`, () => new HttpResponse(null, { status: 204 })),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness agent={agent} onDeleted={onDeleted} onClose={onClose} />);

    await userEvent.type(
      screen.getByLabelText(/type "researcher" to confirm/i),
      agent.name,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith(agent));
    expect(onClose).toHaveBeenCalled();
  });

  test('500 error: inline alert renders and dialog stays open', async () => {
    const agent = anAgent();
    server.use(
      http.delete(`${BASE}/agents/:agentId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness agent={agent} onDeleted={onDeleted} onClose={onClose} />);

    await userEvent.type(
      screen.getByLabelText(/type "researcher" to confirm/i),
      agent.name,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  test('404 NOT_FOUND: inline error renders, onDeleted is NOT called', async () => {
    const agent = anAgent();
    server.use(
      http.delete(`${BASE}/agents/:agentId`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    renderWithProviders(<Harness agent={agent} onDeleted={onDeleted} />);

    await userEvent.type(
      screen.getByLabelText(/type "researcher" to confirm/i),
      agent.name,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
