import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { renderWithProviders } from '@/test/render';
import McpServersPage from './McpServersPage';

const BASE = env.VITE_API_BASE_URL;

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('McpServersPage', () => {
  test('renders all rows; null description renders as em-dash', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({
          items: [
            { name: 'filesystem', description: 'Local filesystem MCP.' },
            { name: 'github', description: null },
            { name: 'postgres', description: 'Read-only Postgres MCP.' },
          ],
        }),
      ),
    );

    renderWithProviders(<McpServersPage />);

    expect(await screen.findByText('filesystem')).toBeInTheDocument();
    expect(screen.getByText('github')).toBeInTheDocument();
    expect(screen.getByText('postgres')).toBeInTheDocument();

    // The null-description row renders the em-dash placeholder.
    const githubRow = screen.getByText('github').closest('tr');
    expect(githubRow).not.toBeNull();
    expect(githubRow!.textContent).toContain('—');
  });

  test('filter narrows; null-description row only matches on name', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({
          items: [
            { name: 'filesystem', description: 'Local filesystem MCP.' },
            { name: 'github', description: null },
          ],
        }),
      ),
    );

    renderWithProviders(<McpServersPage />);

    const input = await screen.findByLabelText(/filter mcp servers/i);

    // Description-only filter excludes the null-description row.
    await userEvent.type(input, 'Local');
    expect(screen.getByText('filesystem')).toBeInTheDocument();
    expect(screen.queryByText('github')).not.toBeInTheDocument();

    // Name match still finds the null-description row.
    await userEvent.clear(input);
    await userEvent.type(input, 'git');
    expect(screen.getByText('github')).toBeInTheDocument();
    expect(screen.queryByText('filesystem')).not.toBeInTheDocument();
  });

  test('empty-state when no match + Clear filter restores list', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({ items: [{ name: 'filesystem', description: 'desc' }] }),
      ),
    );

    renderWithProviders(<McpServersPage />);
    const input = await screen.findByLabelText(/filter mcp servers/i);
    await userEvent.type(input, 'no-such-thing');

    expect(screen.getByText(/no mcp servers match "no-such-thing"/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /clear filter/i }));
    expect(await screen.findByText('filesystem')).toBeInTheDocument();
  });

  test('server-side empty catalog renders "No MCP servers configured" and hides the filter', async () => {
    server.use(http.get(`${BASE}/mcp-servers`, () => HttpResponse.json({ items: [] })));

    renderWithProviders(<McpServersPage />);
    expect(await screen.findByText(/no mcp servers configured/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/filter mcp servers/i)).not.toBeInTheDocument();
  });

  test('INTERNAL_ERROR surfaces as retryable; Retry recovers to success', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/mcp-servers`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({ items: [{ name: 'filesystem', description: 'd' }] });
      }),
    );

    renderWithProviders(<McpServersPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));

    await waitFor(() => expect(screen.getByText('filesystem')).toBeInTheDocument());
  });
});
