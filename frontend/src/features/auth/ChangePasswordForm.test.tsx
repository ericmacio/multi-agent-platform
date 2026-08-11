import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/render';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { _resetToasts } from '@/shared/ui/Toast';
import { ChangePasswordForm } from './ChangePasswordForm';

const BASE = 'http://localhost:8080/api/v1';

function base64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt(): string {
  return [
    base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64url(
      JSON.stringify({
        sub: 'alice@example.com',
        role: 'STANDARD',
        iat: Math.floor(Date.now() / 1000),
        jti: 'j1',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ),
    'sig',
  ].join('.');
}
function aBundle(overrides: Partial<TokenBundle> = {}): TokenBundle {
  return {
    token: makeJwt(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
  vi.restoreAllMocks();
});

describe('ChangePasswordForm', () => {
  test('submit button starts disabled and remains disabled while any rule fails', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    renderWithProviders(<ChangePasswordForm />, { initialEntries: ['/change-password'] });
    const button = screen.getByRole('button', { name: /change password/i });
    expect(button).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/^current password$/i), 'old-password');
    expect(button).toBeDisabled(); // newPassword empty

    await userEvent.type(screen.getByLabelText(/^new password$/i), 'short');
    expect(button).toBeDisabled(); // policy not satisfied
  });

  test('live checklist marks each rule as the user types', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    renderWithProviders(<ChangePasswordForm />, { initialEntries: ['/change-password'] });

    const newPasswordInput = screen.getByLabelText(/^new password$/i);

    // Empty: all rules failing
    expect(screen.getByTestId('rule-length')).toHaveClass('text-text-muted');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-text-muted');
    expect(screen.getByTestId('rule-special')).toHaveClass('text-text-muted');

    await userEvent.type(newPasswordInput, 'A');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-length')).toHaveClass('text-text-muted');

    await userEvent.type(newPasswordInput, 'bcdefghij!');
    expect(screen.getByTestId('rule-length')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-uppercase')).toHaveClass('text-success');
    expect(screen.getByTestId('rule-special')).toHaveClass('text-success');
  });

  test('submit enables only when policy passes AND confirm matches', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    renderWithProviders(<ChangePasswordForm />, { initialEntries: ['/change-password'] });
    const button = screen.getByRole('button', { name: /change password/i });

    await userEvent.type(screen.getByLabelText(/^current password$/i), 'old');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Abcdefghij!');
    expect(button).toBeDisabled(); // confirm still empty

    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'mismatch');
    expect(button).toBeDisabled(); // mismatch

    await userEvent.clear(screen.getByLabelText(/confirm new password/i));
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Abcdefghij!');
    expect(button).not.toBeDisabled();
  });

  test('204 success fires onSuccess and a success toast', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    server.use(http.put(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 204 })));

    const onSuccess = vi.fn();
    renderWithProviders(<ChangePasswordForm onSuccess={onSuccess} />, {
      initialEntries: ['/change-password'],
    });

    await userEvent.type(screen.getByLabelText(/^current password$/i), 'old');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Abcdefghij!');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Abcdefghij!');
    await userEvent.click(screen.getByRole('button', { name: /change password/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(screen.getByTestId('toast-success')).toHaveTextContent(/password changed/i);
  });

  test('400 VALIDATION_ERROR maps server field errors to the right input', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    server.use(
      http.put(`${BASE}/auth/password`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'newPassword', message: 'has been reused recently' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    renderWithProviders(<ChangePasswordForm />, { initialEntries: ['/change-password'] });

    await userEvent.type(screen.getByLabelText(/^current password$/i), 'old');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Abcdefghij!');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Abcdefghij!');
    await userEvent.click(screen.getByRole('button', { name: /change password/i }));

    expect(await screen.findByText(/has been reused recently/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^new password$/i)).toHaveAttribute('aria-invalid', 'true');
  });

  test('429 RATE_LIMITED renders countdown alert and disables submit', async () => {
    tokenStorage.set(aBundle({ mustChangePassword: true }));
    server.use(
      http.put(`${BASE}/auth/password`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1' },
          },
        ),
      ),
    );
    renderWithProviders(<ChangePasswordForm />, { initialEntries: ['/change-password'] });

    await userEvent.type(screen.getByLabelText(/^current password$/i), 'old');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Abcdefghij!');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Abcdefghij!');
    await userEvent.click(screen.getByRole('button', { name: /change password/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/try again in 1s/i);

    const button = screen.getByRole('button', { name: /change password/i });
    expect(button).toBeDisabled();
    await waitFor(() => expect(button).not.toBeDisabled(), { timeout: 2000 });
  });
});
