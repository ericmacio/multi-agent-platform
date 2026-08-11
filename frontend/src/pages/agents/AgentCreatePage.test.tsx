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
import AgentCreatePage from './AgentCreatePage';
import type { Agent } from '@/features/agents/schema';

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

function emptyCatalogs() {
  server.use(
    http.get(`${BASE}/tools`, () => HttpResponse.json({ items: [] })),
    http.get(`${BASE}/mcp-servers`, () => HttpResponse.json({ items: [] })),
    http.get(`${BASE}/agents`, () =>
      HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
    ),
  );
}

function renderPageAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/agents" element={<div data-testid="probe">/agents</div>} />
      <Route path="/agents/new" element={<AgentCreatePage />} />
      <Route path="/agents/:id" element={<div data-testid="probe">/agents/{':id'}</div>} />
    </Routes>,
    { initialEntries: [path] },
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

describe('AgentCreatePage', () => {
  test('happy path: 201 navigates to /agents/<id> and fires success toast', async () => {
    emptyCatalogs();
    const created = anAgent();
    server.use(http.post(`${BASE}/agents`, () => HttpResponse.json(created, { status: 201 })));

    renderPageAt('/agents/new');

    await userEvent.type(screen.getByLabelText(/^name$/i), 'Researcher');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByTestId('probe')).toHaveTextContent('/agents/'),
    );
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/agent created/i);
  });

  test('409 DUPLICATE_AGENT_NAME surfaces the conflict copy and stays on /agents/new', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Duplicate agent name', status: 409, code: 'DUPLICATE_AGENT_NAME' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPageAt('/agents/new');

    await userEvent.type(screen.getByLabelText(/^name$/i), 'dupe');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByText(/duplicate agent name/i)).toBeInTheDocument(),
    );
    // No navigation occurred.
    expect(screen.queryByTestId('probe')).not.toBeInTheDocument();
  });
});
