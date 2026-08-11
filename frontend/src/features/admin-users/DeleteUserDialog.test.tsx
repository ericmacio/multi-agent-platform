import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { DeleteUserDialog } from './DeleteUserDialog';
import type { User } from './schema';

const BASE = env.VITE_API_BASE_URL;

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    email: 'alice@example.com',
    role: 'STANDARD',
    disabled: false,
    mustChangePassword: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function Harness({
  user,
  onDeleted,
  onClose,
}: {
  user: User;
  onDeleted: (u: User) => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(true);
  return (
    <DeleteUserDialog
      user={user}
      open={open}
      onClose={() => {
        setOpen(false);
        onClose?.();
      }}
      onDeleted={onDeleted}
    />
  );
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('DeleteUserDialog', () => {
  test('Delete button is disabled until the email is typed exactly (case-sensitive)', async () => {
    renderWithProviders(<Harness user={aUser()} onDeleted={vi.fn()} />);

    const deleteBtn = screen.getByRole('button', { name: /^delete$/i });
    expect(deleteBtn).toBeDisabled();

    const input = screen.getByLabelText(/type "alice@example\.com" to confirm/i);
    await userEvent.type(input, 'alice@example.co');
    expect(deleteBtn).toBeDisabled();

    await userEvent.type(input, 'm');
    expect(deleteBtn).toBeEnabled();

    // Case-sensitive: wrong casing disables again.
    await userEvent.clear(input);
    await userEvent.type(input, 'Alice@example.com');
    expect(deleteBtn).toBeDisabled();
  });

  test('204 success: onDeleted is called and dialog closes', async () => {
    const user = aUser();
    server.use(
      http.delete(
        `${BASE}/admin/users/:userId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness user={user} onDeleted={onDeleted} onClose={onClose} />);

    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith(user));
    expect(onClose).toHaveBeenCalled();
  });

  test('500 error: inline alert renders and dialog stays open', async () => {
    const user = aUser();
    server.use(
      http.delete(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness user={user} onDeleted={onDeleted} onClose={onClose} />);

    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  test('404 NOT_FOUND: inline alert renders, onDeleted is NOT called', async () => {
    const user = aUser();
    server.use(
      http.delete(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const onDeleted = vi.fn();
    renderWithProviders(<Harness user={user} onDeleted={onDeleted} />);

    await userEvent.type(
      screen.getByLabelText(/type "alice@example\.com" to confirm/i),
      user.email,
    );
    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
