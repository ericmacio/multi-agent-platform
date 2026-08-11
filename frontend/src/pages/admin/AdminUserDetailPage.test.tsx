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
import AdminUserDetailPage from './AdminUserDetailPage';
import type { User } from '@/features/admin-users/schema';

const BASE = env.VITE_API_BASE_URL;
const USER_ID = '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1';

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: USER_ID,
    email: 'alice@example.com',
    role: 'STANDARD',
    disabled: false,
    mustChangePassword: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function renderPage(initialPath = `/admin/users/${USER_ID}`) {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/users" element={<div data-testid="probe">/admin/users</div>} />
      <Route path="/admin/users/:userId" element={<AdminUserDetailPage />} />
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

describe('AdminUserDetailPage', () => {
  test('populated detail renders every field + a two-action CTA bar', async () => {
    const user = aUser({ email: 'alice@example.com', role: 'STANDARD', disabled: false });
    server.use(http.get(`${BASE}/admin/users/:userId`, () => HttpResponse.json(user)));

    renderPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'alice@example.com' })).toBeInTheDocument();
    // Role + status badges.
    expect(screen.getByText('STANDARD')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    // CTA bar: Disable + Delete (STANDARD user).
    expect(screen.getByRole('button', { name: /^disable$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument();
  });

  test('disabled user renders Enable in the CTA bar', async () => {
    const user = aUser({ disabled: true });
    server.use(http.get(`${BASE}/admin/users/:userId`, () => HttpResponse.json(user)));

    renderPage();
    await screen.findByRole('heading', { level: 1 });
    expect(screen.getByRole('button', { name: /^enable$/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^disable$/i })).not.toBeInTheDocument();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
  });

  test('Disable flow: optimistic flip in the summary card + success toast', async () => {
    const state = { user: aUser({ disabled: false }) };
    server.use(
      http.get(`${BASE}/admin/users/:userId`, () => HttpResponse.json(state.user)),
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

    renderPage();
    await screen.findByRole('heading', { level: 1 });

    await userEvent.click(screen.getByRole('button', { name: /^disable$/i }));
    // Modal confirm — take the last matching button (the modal's).
    const confirmButtons = screen.getAllByRole('button', { name: /^disable$/i });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]!);

    await waitFor(() => expect(screen.getByText('Disabled')).toBeInTheDocument());
    // The CTA button label also flips.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^enable$/i })).toBeInTheDocument(),
    );
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/account updated/i);
  });

  test('Delete flow: navigates back to /admin/users + fires success toast', async () => {
    const user = aUser({ email: 'alice@example.com' });
    server.use(
      http.get(`${BASE}/admin/users/:userId`, () => HttpResponse.json(user)),
      http.delete(
        `${BASE}/admin/users/:userId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    renderPage();
    await screen.findByRole('heading', { level: 1 });

    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    // The dialog's Delete button (the only enabled one after typing).
    const deleteButtons = screen.getAllByRole('button', { name: /^delete$/i });
    await userEvent.click(deleteButtons[deleteButtons.length - 1]!);

    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users');
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/user deleted/i);
  });

  test('404 NOT_FOUND: renders "User not found" empty state with a Back link', async () => {
    server.use(
      http.get(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPage();
    expect(await screen.findByText(/user not found/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /back to users/i })).toBeInTheDocument();
  });
});
