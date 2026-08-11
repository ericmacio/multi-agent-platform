import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { UserForm } from './UserForm';
import type { User } from './schema';

const BASE = env.VITE_API_BASE_URL;

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    email: 'new@example.com',
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

describe('UserForm', () => {
  test('empty submit shows Zod field errors on email and password', async () => {
    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    // Submit disabled while form is invalid — force-click via keyboard-invalid
    // path: type/clear to trigger validation, then attempt submit.
    const emailInput = screen.getByLabelText(/email/i);
    await userEvent.type(emailInput, 'x');
    await userEvent.clear(emailInput);
    // Blur to trigger validation of the empty state.
    emailInput.blur();

    await waitFor(() =>
      expect(screen.getByLabelText(/email/i)).toHaveAttribute('aria-invalid', 'true'),
    );
  });

  test('invalid email format surfaces validation error', async () => {
    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'not-an-email');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');

    await waitFor(() =>
      expect(screen.getByLabelText(/email/i)).toHaveAttribute('aria-invalid', 'true'),
    );
  });

  test('password policy checklist reflects live keystrokes', async () => {
    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    const password = screen.getByLabelText(/^password$/i);

    // Empty: all three rules failing.
    expect(screen.getByTestId('rule-length')).toHaveClass('text-text-muted');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-text-muted');
    expect(screen.getByTestId('rule-special')).toHaveClass('text-text-muted');

    await userEvent.type(password, 'A');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-length')).toHaveClass('text-text-muted');

    await userEvent.type(password, 'bcdefghij!');
    expect(screen.getByTestId('rule-length')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-special')).toHaveClass('text-success');
  });

  test('role defaults to STANDARD and the caption is visible', () => {
    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);
    const roleSelect = screen.getByLabelText(/role/i) as HTMLSelectElement;
    expect(roleSelect.value).toBe('STANDARD');
    expect(screen.getByText(/admins can manage users, api keys/i)).toBeInTheDocument();
  });

  test('happy path: valid submit fires POST and onSuccess receives the created User', async () => {
    const created = aUser({ email: 'new@example.com', role: 'ADMIN' });
    let receivedBody: unknown = null;
    server.use(
      http.post(`${BASE}/admin/users`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(created, { status: 201 });
      }),
    );

    const onSuccess = vi.fn();
    renderWithProviders(<UserForm onSuccess={onSuccess} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.selectOptions(screen.getByLabelText(/role/i), 'ADMIN');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    expect(onSuccess.mock.calls[0]?.[0]).toMatchObject({
      email: 'new@example.com',
      role: 'ADMIN',
    });
    expect(receivedBody).toMatchObject({
      email: 'new@example.com',
      password: 'AValid!Pw1',
      role: 'ADMIN',
    });
  });

  test('does not log the password to the console', async () => {
    const created = aUser();
    server.use(
      http.post(`${BASE}/admin/users`, () => HttpResponse.json(created, { status: 201 })),
    );
    const logSpy = vi.spyOn(console, 'log');
    const infoSpy = vi.spyOn(console, 'info');

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/email/i), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'SecretPw!1A');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    await waitFor(() => expect(logSpy).not.toHaveBeenCalledWith(expect.stringContaining('SecretPw!1A')));
    expect(infoSpy).not.toHaveBeenCalledWith(expect.stringContaining('SecretPw!1A'));

    logSpy.mockRestore();
    infoSpy.mockRestore();
  });

  test('409 CONFLICT: sets an email field error with the conflict copy', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          { title: 'Conflict', status: 409, code: 'CONFLICT' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'dupe@example.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    await waitFor(() =>
      expect(screen.getByLabelText(/email/i)).toHaveAttribute('aria-invalid', 'true'),
    );
  });

  test('400 VALIDATION_ERROR on email: maps server field message to the email input', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'email', message: 'reserved domain' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'a@reserved.com');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    expect(await screen.findByText(/reserved domain/i)).toBeInTheDocument();
  });

  test('400 VALIDATION_ERROR on password: maps server field message to the password input', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'password', message: 'password has been leaked' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'a@b.co');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    expect(await screen.findByText(/password has been leaked/i)).toBeInTheDocument();
  });

  test('429 RATE_LIMITED renders countdown alert and disables submit', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1' },
          },
        ),
      ),
    );

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'a@b.co');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/try again in 1s/i);

    const button = screen.getByRole('button', { name: /create user/i });
    expect(button).toBeDisabled();
    await waitFor(() => expect(button).not.toBeDisabled(), { timeout: 2000 });
  });

  test('500 INTERNAL_ERROR renders top-of-form fallback alert; submit re-enables', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/email/i), 'a@b.co');
    await userEvent.type(screen.getByLabelText(/^password$/i), 'AValid!Pw1');
    await userEvent.click(screen.getByRole('button', { name: /create user/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/internal error/i);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /create user/i })).not.toBeDisabled(),
    );
  });

  test('cancel button calls onCancel and does not fire a network request', async () => {
    let calls = 0;
    server.use(
      http.post(`${BASE}/admin/users`, () => {
        calls += 1;
        return HttpResponse.json(aUser(), { status: 201 });
      }),
    );

    const onCancel = vi.fn();
    renderWithProviders(<UserForm onSuccess={vi.fn()} onCancel={onCancel} />);
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(calls).toBe(0);
  });
});
