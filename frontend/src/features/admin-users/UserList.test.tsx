import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { UserList } from './UserList';
import type { User } from './schema';

const BASE = env.VITE_API_BASE_URL;

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    email: 'alice@example.com',
    role: 'STANDARD',
    disabled: false,
    mustChangePassword: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('UserList', () => {
  test('skeleton on first paint, then renders rows with badges', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({
          items: [
            aUser({ email: 'alice@example.com', role: 'STANDARD', disabled: false }),
            aUser({
              id: '22222222-2222-2222-2222-222222222222',
              email: 'admin@example.com',
              role: 'ADMIN',
              disabled: true,
            }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(
      <UserList onView={vi.fn()} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    expect(screen.getByTestId('user-list-loading')).toBeInTheDocument();
    expect(await screen.findByText('alice@example.com')).toBeInTheDocument();
    expect(screen.getByText('admin@example.com')).toBeInTheDocument();
    // Role + status badges present.
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    expect(screen.getByText('STANDARD')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
  });

  test('row click (outside the actions cell) fires onView(user.id)', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({
          items: [aUser({ email: 'alice@example.com' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    const onView = vi.fn();
    renderWithProviders(
      <UserList onView={onView} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    await userEvent.click(await screen.findByText('alice@example.com'));
    expect(onView).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111');
  });

  test('action menu shows Disable for an active user, Enable for a disabled one', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({
          items: [
            aUser({ email: 'active@x.io', disabled: false }),
            aUser({
              id: '22222222-2222-2222-2222-222222222222',
              email: 'disabled@x.io',
              disabled: true,
            }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(
      <UserList onView={vi.fn()} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    await screen.findByText('active@x.io');
    await userEvent.click(screen.getByRole('button', { name: /actions for active@x\.io/i }));
    expect(screen.getByRole('menuitem', { name: /^disable$/i })).toBeInTheDocument();
    // Close menu, then open the other user's.
    await userEvent.keyboard('{Escape}');

    await userEvent.click(
      screen.getByRole('button', { name: /actions for disabled@x\.io/i }),
    );
    expect(screen.getByRole('menuitem', { name: /^enable$/i })).toBeInTheDocument();
  });

  test('Load more appends the next page', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [aUser({ email: 'a@x.io' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [
            aUser({ id: '22222222-2222-2222-2222-222222222222', email: 'b@x.io' }),
          ],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(
      <UserList onView={vi.fn()} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    await screen.findByText('a@x.io');
    await userEvent.click(screen.getByRole('button', { name: /load more/i }));
    await waitFor(() => expect(screen.getByText('b@x.io')).toBeInTheDocument());
  });

  test('error state renders alert + Retry, recovers on success', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/admin/users`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({
          items: [aUser({ email: 'a@x.io' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(
      <UserList onView={vi.fn()} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('a@x.io')).toBeInTheDocument());
  });

  test('returns null when the flattened list is empty (page owns the empty state)', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    const { container } = renderWithProviders(
      <UserList onView={vi.fn()} onToggleDisabled={vi.fn()} onDelete={vi.fn()} />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-testid="user-list-loading"]')).toBeNull(),
    );
    expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
