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
import AdminUserCreatePage from './AdminUserCreatePage';
import type { User } from '@/features/admin-users/schema';

const BASE = env.VITE_API_BASE_URL;

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'new@example.com',
    role: 'STANDARD',
    disabled: false,
    mustChangePassword: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function renderPage(initialPath = '/admin/users/new') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/users" element={<div data-testid="probe">/admin/users</div>} />
      <Route path="/admin/users/new" element={<AdminUserCreatePage />} />
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

describe('AdminUserCreatePage', () => {
  test('valid submit navigates to /admin/users/:id + fires success toast', async () => {
    const created = aUser({ email: 'new@example.com' });
    server.use(
      http.post(`${BASE}/admin/users`, () => HttpResponse.json(created, { status: 201 })),
    );

    renderPage();
    await userEvent.type(screen.getByLabelText(/email/i), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /^create user$/i }));

    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users/X');
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/user created/i);
  });

  test('409 CONFLICT: surfaces the email conflict inline on the form', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          { title: 'Conflict', status: 409, code: 'CONFLICT' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderPage();
    await userEvent.type(screen.getByLabelText(/email/i), 'dupe@example.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /^create user$/i }));

    await waitFor(() =>
      expect(screen.getByLabelText(/email/i)).toHaveAttribute('aria-invalid', 'true'),
    );
    // Location should NOT have moved.
    expect(screen.queryByTestId('probe')).not.toBeInTheDocument();
  });

  test('Cancel returns to /admin/users', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('/admin/users');
  });
});
