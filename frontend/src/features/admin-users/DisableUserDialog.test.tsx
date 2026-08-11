import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { QueryClient } from '@tanstack/react-query';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { qk } from '@/shared/api/queryKeys';
import { DisableUserDialog } from './DisableUserDialog';
import type { User } from './schema';

/**
 * Test queryClient with a non-zero gcTime — the shared `makeTestQueryClient`
 * uses `gcTime: 0`, which garbage-collects a seeded (observer-less) query
 * before the mutation's `onMutate` can read it. A long gcTime keeps the
 * seeded cache alive across the mutation lifecycle.
 */
function longLivedClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, gcTime: 5 * 60 * 1000 },
      mutations: { retry: false },
    },
  });
}

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
  onDone,
  onClose,
}: {
  user: User;
  onDone: (u: User) => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(true);
  return (
    <DisableUserDialog
      user={user}
      open={open}
      onClose={() => {
        setOpen(false);
        onClose?.();
      }}
      onDone={onDone}
    />
  );
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('DisableUserDialog', () => {
  test('disable direction: shows "Disable" body + destructive confirm', () => {
    renderWithProviders(<Harness user={aUser({ disabled: false })} onDone={vi.fn()} />);

    expect(
      screen.getByText(/disable alice@example\.com\? they will not be able to sign in/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^disable$/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^enable$/i })).not.toBeInTheDocument();
  });

  test('enable direction: shows "Re-enable" body + primary confirm', () => {
    renderWithProviders(<Harness user={aUser({ disabled: true })} onDone={vi.fn()} />);

    expect(
      screen.getByText(/re-enable alice@example\.com\? they will be able to sign in again/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^enable$/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^disable$/i })).not.toBeInTheDocument();
  });

  test('optimistic flip: 200 success calls onDone and closes the dialog', async () => {
    const user = aUser({ disabled: false });
    server.use(
      http.patch(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json({ ...user, disabled: true, updatedAt: '2026-01-02T00:00:00Z' }),
      ),
    );

    const onDone = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness user={user} onDone={onDone} onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: /^disable$/i }));

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(user));
    expect(onClose).toHaveBeenCalled();
  });

  test('500 error: rolls back the optimistic cache and keeps the dialog open with an inline alert', async () => {
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.patch(`${BASE}/admin/users/:userId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    const user = aUser({ disabled: false });
    const onDone = vi.fn();
    const onClose = vi.fn();
    const queryClient = longLivedClient();
    // Seed the detail cache BEFORE mount so the rollback assertion has an
    // observable target that survives the mutation lifecycle.
    queryClient.setQueryData(qk.admin.users.byId(user.id), user);
    renderWithProviders(<Harness user={user} onDone={onDone} onClose={onClose} />, {
      queryClient,
    });

    await userEvent.click(screen.getByRole('button', { name: /^disable$/i }));

    // In-flight: optimistic flip visible on the seeded detail cache.
    await waitFor(() => {
      const cached = queryClient.getQueryData<User>(qk.admin.users.byId(user.id));
      expect(cached?.disabled).toBe(true);
    });

    releaseError?.();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // Rollback: the detail cache is back to `disabled: false`.
    expect(queryClient.getQueryData<User>(qk.admin.users.byId(user.id))?.disabled).toBe(false);
    // Dialog stays open; onDone / onClose not called.
    expect(onDone).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
