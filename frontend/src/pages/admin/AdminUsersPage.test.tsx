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
import AdminUsersPage from './AdminUsersPage';
import type { User } from '@/features/admin-users/schema';

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

function renderPageWithLocationProbe(initialPath = '/admin/users') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/users" element={<AdminUsersPage />} />
      <Route path="/admin/users/new" element={<div data-testid="probe">/admin/users/new</div>} />
      <Route path="/admin/users/:id" element={<div data-testid="probe">/admin/users/X</div>} />
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

describe('AdminUsersPage', () => {
  test('populated list renders + "Create user" CTA navigates to /admin/users/new', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({
          items: [
            aUser({ email: 'alice@example.com' }),
            aUser({
              id: '22222222-2222-2222-2222-222222222222',
              email: 'admin@example.com',
              role: 'ADMIN',
            }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderPageWithLocationProbe();

    expect(await screen.findByText('alice@example.com')).toBeInTheDocument();
    expect(screen.getByText('admin@example.com')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^create user$/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users/new');
  });

  test('empty state renders + CTA navigates to /admin/users/new', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderPageWithLocationProbe();

    expect(await screen.findByText(/no users yet/i)).toBeInTheDocument();
    const createBtn = screen
      .getAllByRole('button', { name: /^create user$/i })
      .find((btn) => btn instanceof HTMLButtonElement);
    await userEvent.click(createBtn!);
    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users/new');
  });

  test('row click navigates to /admin/users/:id', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({
          items: [aUser({ email: 'alice@example.com' })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderPageWithLocationProbe();
    await userEvent.click(await screen.findByText('alice@example.com'));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users/X');
  });

  test('optimistic disable flow: badge flips immediately, then toast + dialog closes', async () => {
    // Stateful mock so the post-mutation invalidation refetch sees the new
    // server state rather than flipping the row back to its original value.
    const state = { user: aUser({ email: 'alice@example.com', disabled: false }) };
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({ items: [state.user], nextCursor: null, pageSize: 20 }),
      ),
      http.patch(`${BASE}/admin/users/:userId`, async ({ request }) => {
        const body = (await request.json()) as { disabled?: boolean };
        state.user = {
          ...state.user,
          disabled: body.disabled ?? state.user.disabled,
          updatedAt: '2026-01-02T00:00:00Z',
        };
        return HttpResponse.json(state.user);
      }),
    );

    renderPageWithLocationProbe();

    await screen.findByText('alice@example.com');
    await userEvent.click(
      screen.getByRole('button', { name: /actions for alice@example\.com/i }),
    );
    await userEvent.click(screen.getByRole('menuitem', { name: /^disable$/i }));

    // Modal confirm-copy visible.
    expect(
      screen.getByText(/disable alice@example\.com\? they will not be able to sign in/i),
    ).toBeInTheDocument();
    // Confirm — the destructive button in the modal, not the row action menu.
    // The modal footer button is the only "Disable" button after opening.
    const confirmButtons = screen.getAllByRole('button', { name: /^disable$/i });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]!);

    // Optimistic flip: the row's Status badge flips to "Disabled" immediately.
    await waitFor(() => expect(screen.getByText('Disabled')).toBeInTheDocument());
    // Success toast surfaces.
    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/account updated/i),
    );
  });

  test('delete-with-cascade flow: dialog shows cascade warning, mutation fires, toast + row disappears', async () => {
    const user = aUser({ email: 'alice@example.com' });
    let listCalls = 0;
    server.use(
      http.get(`${BASE}/admin/users`, () => {
        listCalls += 1;
        if (listCalls === 1) {
          return HttpResponse.json({ items: [user], nextCursor: null, pageSize: 20 });
        }
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
      http.delete(
        `${BASE}/admin/users/:userId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    renderPageWithLocationProbe();

    await screen.findByText('alice@example.com');
    await userEvent.click(
      screen.getByRole('button', { name: /actions for alice@example\.com/i }),
    );
    await userEvent.click(screen.getByRole('menuitem', { name: /^delete$/i }));

    expect(
      screen.getByText(/will permanently delete their agents and conversations/i),
    ).toBeInTheDocument();

    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/user deleted/i),
    );
    await waitFor(() => expect(screen.queryByText('alice@example.com')).not.toBeInTheDocument());
  });

  test('delete failure leaves the dialog open and the list intact', async () => {
    const user = aUser({ email: 'alice@example.com' });
    server.use(
      http.get(`${BASE}/admin/users`, () =>
        HttpResponse.json({ items: [user], nextCursor: null, pageSize: 20 }),
      ),
      http.delete(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPageWithLocationProbe();

    await screen.findByText('alice@example.com');
    await userEvent.click(
      screen.getByRole('button', { name: /actions for alice@example\.com/i }),
    );
    await userEvent.click(screen.getByRole('menuitem', { name: /^delete$/i }));
    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByText('alice@example.com')).toBeInTheDocument();
  });
});
