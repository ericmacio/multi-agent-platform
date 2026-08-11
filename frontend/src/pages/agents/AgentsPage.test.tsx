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
import AgentsPage from './AgentsPage';
import type { Agent } from '@/features/agents/schema';

const BASE = env.VITE_API_BASE_URL;

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Alpha',
    description: 'first agent',
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

/**
 * Renders AgentsPage with a sibling route printing the current URL so we can
 * assert navigation without depending on the real downstream pages.
 */
function renderPageWithLocationProbe(initialPath = '/agents') {
  return renderWithProviders(
    <Routes>
      <Route path="/agents" element={<AgentsPage />} />
      <Route path="/agents/new" element={<div data-testid="probe">/agents/new</div>} />
      <Route path="/agents/:id" element={<div data-testid="probe">/agents/X</div>} />
      <Route path="/agents/:id/edit" element={<div data-testid="probe">/agents/X/edit</div>} />
      <Route path="/chat/new" element={<div data-testid="probe">/chat/new</div>} />
    </Routes>,
    { initialEntries: [initialPath] },
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

describe('AgentsPage', () => {
  test('empty state renders + CTA navigates to /agents/new', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderPageWithLocationProbe();

    expect(await screen.findByText(/you don't have any agents yet/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /create your first agent/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/agents/new');
  });

  test('populated list renders + "New agent" header CTA navigates to /agents/new', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({
          items: [anAgent({ name: 'Alpha' }), anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Beta' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderPageWithLocationProbe();

    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^new agent$/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/agents/new');
  });

  test('delete flow: dialog confirms cascade, mutation fires, success toast renders', async () => {
    const agent = anAgent({ name: 'Alpha' });
    let listCalls = 0;
    server.use(
      http.get(`${BASE}/agents`, () => {
        listCalls += 1;
        // First call: return Alpha; subsequent calls (after invalidate): empty.
        if (listCalls === 1) {
          return HttpResponse.json({ items: [agent], nextCursor: null, pageSize: 20 });
        }
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
      http.delete(`${BASE}/agents/:agentId`, () => new HttpResponse(null, { status: 204 })),
    );

    renderPageWithLocationProbe();

    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('button', { name: /actions for alpha/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /delete/i }));

    expect(
      screen.getByText(/deleting this agent will also delete every conversation/i),
    ).toBeInTheDocument();

    await userEvent.type(
      screen.getByLabelText(/type "alpha" to confirm/i),
      'Alpha',
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    // After 204 success: success toast, list refetches, Alpha disappears.
    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/agent deleted/i),
    );
    await waitFor(() => expect(screen.queryByText('Alpha')).not.toBeInTheDocument());
  });

  test('delete failure leaves the dialog open and the list intact', async () => {
    const agent = anAgent({ name: 'Alpha' });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [agent], nextCursor: null, pageSize: 20 }),
      ),
      http.delete(`${BASE}/agents/:agentId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPageWithLocationProbe();

    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('button', { name: /actions for alpha/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /delete/i }));
    await userEvent.type(
      screen.getByLabelText(/type "alpha" to confirm/i),
      'Alpha',
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // Dialog still open; list still shows Alpha.
    expect(screen.getByText('Alpha')).toBeInTheDocument();
  });
});
