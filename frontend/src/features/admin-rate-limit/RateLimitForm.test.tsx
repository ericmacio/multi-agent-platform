import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { RateLimitForm } from './RateLimitForm';
import type { RateLimitConfig } from './schema';

const BASE = env.VITE_API_BASE_URL;

function aConfig(overrides: Partial<RateLimitConfig> = {}): RateLimitConfig {
  return {
    perMinute: 60,
    perHour: 3600,
    updatedAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    updatedBy: 'admin-uuid-1',
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('RateLimitForm', () => {
  test('initial render: inputs seeded, Save disabled, Last updated caption present', () => {
    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    expect(screen.getByLabelText(/requests per minute/i)).toHaveValue(60);
    expect(screen.getByLabelText(/requests per hour/i)).toHaveValue(3600);
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
    expect(screen.getByText(/last updated/i)).toBeInTheDocument();
  });

  test('caption with updatedBy renders the uuid in monospace', () => {
    renderWithProviders(
      <RateLimitForm defaults={aConfig({ updatedBy: 'the-uuid-42' })} />,
    );
    const code = screen.getByText('the-uuid-42');
    expect(code.tagName).toBe('CODE');
    expect(code).toHaveClass('font-mono');
  });

  test('caption without updatedBy omits the "by …" segment', () => {
    renderWithProviders(
      <RateLimitForm defaults={aConfig({ updatedBy: null })} />,
    );
    expect(screen.queryByText(/by null/i)).toBeNull();
    expect(screen.queryByText(/by unknown/i)).toBeNull();
    expect(screen.queryByText(/\bby\b/i)).toBeNull();
  });

  test('dirty → Save enables and Reset appears', async () => {
    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled(),
    );
    expect(screen.getByRole('button', { name: /^reset$/i })).toBeInTheDocument();
  });

  test('Reset restores the original defaults and disables Save', async () => {
    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled(),
    );

    await userEvent.click(screen.getByRole('button', { name: /^reset$/i }));
    expect(screen.getByLabelText(/requests per minute/i)).toHaveValue(60);
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
  });

  test('Zod min=1: setting perMinute to 0 surfaces the min error', async () => {
    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '0');

    await waitFor(() =>
      expect(screen.getByLabelText(/requests per minute/i)).toHaveAttribute(
        'aria-invalid',
        'true',
      ),
    );
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
  });

  test('Zod integer: perMinute=1.5 surfaces an integer error', async () => {
    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '1.5');

    await waitFor(() =>
      expect(screen.getByLabelText(/requests per minute/i)).toHaveAttribute(
        'aria-invalid',
        'true',
      ),
    );
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
  });

  test('happy path: Save fires PUT, onSaved receives the new config, form resets dirty', async () => {
    const updated: RateLimitConfig = {
      perMinute: 120,
      perHour: 3600,
      updatedAt: new Date().toISOString(),
      updatedBy: 'admin-uuid-1',
    };
    let receivedBody: unknown = null;
    server.use(
      http.put(`${BASE}/admin/rate-limit`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(updated);
      }),
    );

    const onSaved = vi.fn();
    renderWithProviders(<RateLimitForm defaults={aConfig()} onSaved={onSaved} />);

    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');

    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(onSaved.mock.calls[0]?.[0]).toMatchObject({ perMinute: 120, perHour: 3600 });
    expect(receivedBody).toMatchObject({ perMinute: 120, perHour: 3600 });

    // Dirty flag clears — Save button disables again.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled(),
    );
  });

  test('400 VALIDATION_ERROR on perMinute: field-scoped error routed to the input', async () => {
    server.use(
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'perMinute', message: 'server-side-only rule violated' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(
      await screen.findByText(/server-side-only rule violated/i),
    ).toBeInTheDocument();
  });

  test('429 RATE_LIMITED countdown: alert renders, Save disabled, re-enables on zero', async () => {
    server.use(
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1' },
          },
        ),
      ),
    );

    renderWithProviders(<RateLimitForm defaults={aConfig()} />);

    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    const alert = await screen.findByText(/try again in 1s/i);
    expect(alert).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();

    // Wait for the real-time countdown to elapse — the alert vanishes and
    // Save re-enables. 2s upper bound leaves comfortable headroom for CI.
    await waitFor(
      () => expect(screen.queryByText(/try again in/i)).not.toBeInTheDocument(),
      { timeout: 2000 },
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled(),
    );
  });

  test('500 INTERNAL_ERROR: top-of-form alert renders, Save re-enables', async () => {
    server.use(
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<RateLimitForm defaults={aConfig()} />);
    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/internal error/i);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled(),
    );
  });
});
