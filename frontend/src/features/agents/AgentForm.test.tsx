import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { AgentForm } from './AgentForm';
import type { Agent } from './schema';

const BASE = env.VITE_API_BASE_URL;

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Researcher',
    description: 'Reads docs.',
    systemPrompt: 'You are a researcher.',
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

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('AgentForm — create mode', () => {
  test('empty submit shows Zod field errors on name, description, systemPrompt', async () => {
    emptyCatalogs();
    renderWithProviders(
      <AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    // Zod surfaces ".min(1)" errors on the three required string fields.
    await waitFor(() =>
      expect(screen.getByLabelText(/^name$/i)).toHaveAttribute('aria-invalid', 'true'),
    );
    expect(screen.getByLabelText(/description/i)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(/system prompt/i)).toHaveAttribute('aria-invalid', 'true');
  });

  test('happy path: submit valid values fires POST and onSuccess receives the new Agent', async () => {
    emptyCatalogs();
    const created = anAgent({ name: 'New One' });
    server.use(http.post(`${BASE}/agents`, () => HttpResponse.json(created, { status: 201 })));

    const onSuccess = vi.fn();
    renderWithProviders(<AgentForm mode="create" onSuccess={onSuccess} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'New One');
    await userEvent.type(screen.getByLabelText(/description/i), 'desc');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 'prompt');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    expect(onSuccess.mock.calls[0]?.[0]).toMatchObject({ name: 'New One' });
  });

  test('409 DUPLICATE_AGENT_NAME: sets a name field error with the conflict copy', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Duplicate agent name', status: 409, code: 'DUPLICATE_AGENT_NAME' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'dupe');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByLabelText(/^name$/i)).toHaveAttribute('aria-invalid', 'true'),
    );
    // The conflict copy from errorCopy is surfaced near the name field.
    expect(screen.getByText(/duplicate agent name/i)).toBeInTheDocument();
  });

  test('409 NESTED_TEAM_FORBIDDEN: surfaces an error in the Team section', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Nested team forbidden', status: 409, code: 'NESTED_TEAM_FORBIDDEN' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() => expect(screen.getByText(/nested team forbidden/i)).toBeInTheDocument());
  });

  test('409 CROSS_OWNER_TEAM_MEMBER: surfaces an error in the Team section', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Team member not allowed', status: 409, code: 'CROSS_OWNER_TEAM_MEMBER' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByText(/team member not allowed/i)).toBeInTheDocument(),
    );
  });

  test('400 VALIDATION_ERROR with a known field maps to that field', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'systemPrompt', message: 'must not contain XYZ' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() => expect(screen.getByText(/must not contain XYZ/i)).toBeInTheDocument());
  });

  test('400 VALIDATION_ERROR with an unknown field shows the top-of-form fallback alert', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'mysteryField', message: 'oops' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByText(/some fields couldn't be saved/i)).toBeInTheDocument(),
    );
  });

  test('429 RATE_LIMITED with Retry-After: 3 renders the countdown alert and disables submit', async () => {
    emptyCatalogs();
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '3' },
          },
        ),
      ),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() =>
      expect(screen.getByText(/too many requests\. try again in 3s/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /create agent/i })).toBeDisabled();
  });

  test('"Use platform default" toggle hides + nulls the four model fields on submit', async () => {
    emptyCatalogs();
    let capturedBody: unknown = null;
    server.use(
      http.post(`${BASE}/agents`, async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json(anAgent(), { status: 201 });
      }),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={vi.fn()} />);

    // Toggle is on by default in create mode (all 4 model fields null) — fields hidden.
    expect(screen.queryByLabelText(/^model$/i)).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/^name$/i), 'a');
    await userEvent.type(screen.getByLabelText(/description/i), 'd');
    await userEvent.type(screen.getByLabelText(/system prompt/i), 's');
    await userEvent.click(screen.getByRole('button', { name: /create agent/i }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody).toMatchObject({
      llmModel: null,
      temperature: null,
      maxOutputTokens: null,
      topP: null,
    });
  });

  test('Cancel calls onCancel and does not fire any network request', async () => {
    emptyCatalogs();
    const onCancel = vi.fn();
    let createCalled = false;
    server.use(
      http.post(`${BASE}/agents`, () => {
        createCalled = true;
        return HttpResponse.json(anAgent(), { status: 201 });
      }),
    );

    renderWithProviders(<AgentForm mode="create" onSuccess={vi.fn()} onCancel={onCancel} />);

    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onCancel).toHaveBeenCalled();
    expect(createCalled).toBe(false);
  });
});

describe('AgentForm — edit mode', () => {
  test('initial values pre-fill the form', async () => {
    emptyCatalogs();
    const initial = anAgent({
      name: 'Existing',
      description: 'Existing desc',
      systemPrompt: 'Existing prompt',
      memorySize: 24,
    });
    renderWithProviders(
      <AgentForm mode="edit" initial={initial} onSuccess={vi.fn()} onCancel={vi.fn()} />,
    );

    expect(screen.getByLabelText(/^name$/i)).toHaveValue('Existing');
    expect(screen.getByLabelText(/description/i)).toHaveValue('Existing desc');
    expect(screen.getByLabelText(/system prompt/i)).toHaveValue('Existing prompt');
    expect(screen.getByLabelText(/memory size/i)).toHaveValue(24);
    expect(screen.getByRole('button', { name: /save/i })).toBeInTheDocument();
  });

  test('submit fires PUT against the agent id and onSuccess receives the updated Agent', async () => {
    emptyCatalogs();
    const initial = anAgent({ name: 'Existing' });
    const updated = anAgent({ ...initial, name: 'Renamed' });
    let putCalled = false;
    server.use(
      http.put(`${BASE}/agents/:agentId`, ({ params }) => {
        putCalled = true;
        expect(params.agentId).toBe(initial.id);
        return HttpResponse.json(updated);
      }),
    );

    const onSuccess = vi.fn();
    renderWithProviders(
      <AgentForm mode="edit" initial={initial} onSuccess={onSuccess} onCancel={vi.fn()} />,
    );

    await userEvent.clear(screen.getByLabelText(/^name$/i));
    await userEvent.type(screen.getByLabelText(/^name$/i), 'Renamed');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(putCalled).toBe(true));
    expect(onSuccess).toHaveBeenCalledWith(updated);
  });
});
