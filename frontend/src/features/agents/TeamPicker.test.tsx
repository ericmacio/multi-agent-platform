import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { TeamPicker } from './TeamPicker';
import type { Agent } from './schema';

const BASE = env.VITE_API_BASE_URL;

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Anon',
    description: 'A helper.',
    systemPrompt: 'You help.',
    memorySize: 12,
    tools: [],
    enabledMcpServers: [],
    team: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function Harness({
  initial = [],
  excludeAgentId,
  disabled = false,
  onChange,
}: {
  initial?: string[];
  excludeAgentId?: string;
  disabled?: boolean;
  onChange?: (next: string[]) => void;
}) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <TeamPicker
      value={value}
      excludeAgentId={excludeAgentId}
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

describe('TeamPicker', () => {
  test('excludeAgentId is not rendered as a candidate', async () => {
    const self = anAgent({ id: '11111111-1111-1111-1111-111111111111', name: 'Self' });
    const other = anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Other' });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [self, other], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderWithProviders(<Harness excludeAgentId={self.id} />);

    expect(await screen.findByText('Other')).toBeInTheDocument();
    expect(screen.queryByText('Self')).not.toBeInTheDocument();
  });

  test('nested-team candidate is disabled with tooltip; clicking does not fire onChange', async () => {
    const open = anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Open' });
    const withTeam = anAgent({
      id: '33333333-3333-3333-3333-333333333333',
      name: 'Boss',
      team: ['44444444-4444-4444-4444-444444444444'],
    });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [open, withTeam], nextCursor: null, pageSize: 20 }),
      ),
    );

    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} />);

    await screen.findByText('Boss');
    const bossRow = screen.getByText('Boss').closest('label');
    expect(bossRow).not.toBeNull();
    const bossCheckbox = bossRow!.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(bossCheckbox.disabled).toBe(true);

    // Hover to surface the tooltip (200ms open delay).
    await userEvent.hover(bossRow!);
    await waitFor(() =>
      expect(screen.getByRole('tooltip')).toHaveTextContent(/has a team of its own/i),
    );

    // Clicking the disabled row does not toggle.
    await userEvent.click(screen.getByText('Boss'));
    expect(onChange).not.toHaveBeenCalled();
  });

  test('toggling a normal candidate fires onChange with the agent id', async () => {
    const a = anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Alpha' });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [a], nextCursor: null, pageSize: 20 }),
      ),
    );

    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} />);

    await screen.findByText('Alpha');
    await userEvent.click(screen.getByText('Alpha'));
    expect(onChange).toHaveBeenLastCalledWith([a.id]);
  });

  test('filter + "Show only selected" compose', async () => {
    const a = anAgent({
      id: '22222222-2222-2222-2222-222222222222',
      name: 'Alpha',
      description: 'first',
    });
    const b = anAgent({
      id: '33333333-3333-3333-3333-333333333333',
      name: 'Beta',
      description: 'second',
    });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [a, b], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderWithProviders(<Harness initial={[a.id]} />);

    await screen.findByText('Alpha');
    // Initial state: both candidates visible.
    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();

    // "Show only selected" filters to Alpha only.
    await userEvent.click(screen.getByLabelText(/show only selected/i));
    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.queryByText('Beta')).not.toBeInTheDocument();

    // Filter for "beta" while "Show only selected" is on → no matches.
    await userEvent.type(screen.getByLabelText(/filter agents/i), 'beta');
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument();
    expect(screen.queryByText('Beta')).not.toBeInTheDocument();
    expect(screen.getByText(/no agents match "beta"/i)).toBeInTheDocument();
  });

  test('pagination drains to the full union of pages', async () => {
    const page1 = [
      anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'Alpha' }),
    ];
    const page2 = [
      anAgent({ id: '33333333-3333-3333-3333-333333333333', name: 'Beta' }),
    ];
    server.use(
      http.get(`${BASE}/agents`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({ items: page1, nextCursor: 'c2', pageSize: 20 });
        }
        return HttpResponse.json({ items: page2, nextCursor: null, pageSize: 20 });
      }),
    );

    renderWithProviders(<Harness />);
    // After drain, both pages' agents are visible.
    await waitFor(() => expect(screen.getByText('Beta')).toBeInTheDocument());
    expect(screen.getByText('Alpha')).toBeInTheDocument();
  });

  test('all-pages-loaded empty (only self exists) renders the "no candidates" empty state', async () => {
    const self = anAgent({ id: '11111111-1111-1111-1111-111111111111', name: 'Self' });
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [self], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderWithProviders(<Harness excludeAgentId={self.id} />);

    expect(await screen.findByText(/no other agents to delegate to/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/filter agents/i)).not.toBeInTheDocument();
  });
});
