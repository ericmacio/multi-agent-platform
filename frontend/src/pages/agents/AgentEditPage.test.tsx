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
import AgentEditPage from './AgentEditPage';
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
      <Route path="/agents/:agentId" element={<div data-testid="probe">/agents/{':agentId'}</div>} />
      <Route path="/agents/:agentId/edit" element={<AgentEditPage />} />
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

describe('AgentEditPage', () => {
  test('mount pre-fills the form with the fetched agent', async () => {
    emptyCatalogs();
    const initial = anAgent({ name: 'Existing', description: 'Existing desc' });
    server.use(http.get(`${BASE}/agents/:id`, () => HttpResponse.json(initial)));

    renderPageAt(`/agents/${initial.id}/edit`);

    expect(await screen.findByDisplayValue('Existing')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Existing desc')).toBeInTheDocument();
  });

  test('saving valid edits fires PUT and navigates back to /agents/<id>', async () => {
    emptyCatalogs();
    const initial = anAgent({ name: 'Existing' });
    const updated = anAgent({ ...initial, name: 'Renamed' });
    server.use(
      http.get(`${BASE}/agents/:id`, () => HttpResponse.json(initial)),
      http.put(`${BASE}/agents/:id`, () => HttpResponse.json(updated)),
    );

    renderPageAt(`/agents/${initial.id}/edit`);

    const nameInput = await screen.findByDisplayValue('Existing');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Renamed');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() =>
      expect(screen.getByTestId('probe')).toHaveTextContent('/agents/'),
    );
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/agent updated/i);
  });

  test('404 NOT_FOUND renders the empty state with a Back link', async () => {
    emptyCatalogs();
    server.use(
      http.get(`${BASE}/agents/:id`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPageAt('/agents/abc/edit');

    expect(await screen.findByText(/agent not found/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /back to agents/i })).toHaveAttribute(
      'href',
      '/agents',
    );
  });
});
