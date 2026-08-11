import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { McpServerPicker } from './McpServerPicker';

const BASE = env.VITE_API_BASE_URL;

function Harness({
  initial = [],
  onChange,
  disabled = false,
}: {
  initial?: string[];
  onChange?: (next: string[]) => void;
  disabled?: boolean;
}) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <McpServerPicker
      value={value}
      disabled={disabled}
      onChange={(next) => {
        setValue(next);
        onChange?.(next);
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

describe('McpServerPicker', () => {
  test('selecting/deselecting toggles value', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({
          items: [
            { name: 'filesystem', description: 'd' },
            { name: 'github', description: null },
          ],
        }),
      ),
    );
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} />);

    await screen.findByText('filesystem');
    await userEvent.click(screen.getByText('filesystem'));
    expect(onChange).toHaveBeenLastCalledWith(['filesystem']);
    await userEvent.click(screen.getByText('filesystem'));
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  test('null-description row renders em-dash; name match still filters it in', async () => {
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
    renderWithProviders(<Harness />);

    expect(await screen.findByText('github')).toBeInTheDocument();
    const githubLi = screen.getByText('github').closest('li');
    expect(githubLi).not.toBeNull();
    expect(githubLi!.textContent).toContain('—');

    await userEvent.type(screen.getByLabelText(/filter mcp servers/i), 'git');
    expect(screen.getByText('github')).toBeInTheDocument();
    expect(screen.queryByText('filesystem')).not.toBeInTheDocument();
  });

  test('selected-count badge updates synchronously with value', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({ items: [{ name: 'filesystem', description: 'd' }] }),
      ),
    );
    renderWithProviders(<Harness />);

    await screen.findByText('filesystem');
    expect(screen.getByTestId('mcp-server-picker-count')).toHaveTextContent('0 selected');
    await userEvent.click(screen.getByText('filesystem'));
    expect(screen.getByTestId('mcp-server-picker-count')).toHaveTextContent('1 selected');
  });

  test('loading state renders skeleton rows', async () => {
    server.use(http.get(`${BASE}/mcp-servers`, () => new Promise<HttpResponse<null>>(() => {})));
    renderWithProviders(<Harness />);
    expect(await screen.findByTestId('mcp-server-picker-loading')).toBeInTheDocument();
  });

  test('error state renders Retry and recovers on success', async () => {
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

    renderWithProviders(<Harness />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('filesystem')).toBeInTheDocument());
  });

  test('empty catalog renders "No MCP servers configured"', async () => {
    server.use(http.get(`${BASE}/mcp-servers`, () => HttpResponse.json({ items: [] })));
    renderWithProviders(<Harness />);
    expect(await screen.findByText(/no mcp servers configured/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/filter mcp servers/i)).not.toBeInTheDocument();
  });

  test('filter no-match shows Clear filter affordance', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({ items: [{ name: 'filesystem', description: 'd' }] }),
      ),
    );
    renderWithProviders(<Harness />);

    await screen.findByText('filesystem');
    await userEvent.type(screen.getByLabelText(/filter mcp servers/i), 'zzz');
    expect(screen.getByText(/no mcp servers match "zzz"/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /clear filter/i }));
    expect(await screen.findByText('filesystem')).toBeInTheDocument();
  });

  test('disabled prop prevents onChange when clicking', async () => {
    server.use(
      http.get(`${BASE}/mcp-servers`, () =>
        HttpResponse.json({ items: [{ name: 'filesystem', description: 'd' }] }),
      ),
    );
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} disabled />);

    await screen.findByText('filesystem');
    await userEvent.click(screen.getByText('filesystem'));
    expect(onChange).not.toHaveBeenCalled();
  });
});
