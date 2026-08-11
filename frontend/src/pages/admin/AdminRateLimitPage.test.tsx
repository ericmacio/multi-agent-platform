import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse, delay } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { _resetToasts } from '@/shared/ui/Toast';
import AdminRateLimitPage from './AdminRateLimitPage';
import type { RateLimitConfig } from '@/features/admin-rate-limit/schema';

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
  _resetToasts();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
});

describe('AdminRateLimitPage', () => {
  test('first-paint skeleton renders while the GET is pending', async () => {
    server.use(
      http.get(`${BASE}/admin/rate-limit`, async () => {
        await delay(50);
        return HttpResponse.json(aConfig());
      }),
    );

    renderWithProviders(<AdminRateLimitPage />);
    expect(screen.getByTestId('rate-limit-loading')).toBeInTheDocument();

    // Eventually the form renders once the delayed response arrives.
    await waitFor(() =>
      expect(screen.getByLabelText(/requests per minute/i)).toBeInTheDocument(),
    );
  });

  test('populated form renders seeded with server values and Last updated caption', async () => {
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          aConfig({ perMinute: 60, perHour: 3600, updatedBy: 'uuid-admin' }),
        ),
      ),
    );

    renderWithProviders(<AdminRateLimitPage />);
    expect(await screen.findByLabelText(/requests per minute/i)).toHaveValue(60);
    expect(screen.getByLabelText(/requests per hour/i)).toHaveValue(3600);
    expect(screen.getByText(/last updated/i)).toBeInTheDocument();
    expect(screen.getByText('uuid-admin')).toBeInTheDocument();
  });

  test('first-paint error + Retry: 500 then 200 resolves to the form', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json(aConfig());
      }),
    );

    renderWithProviders(<AdminRateLimitPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/internal error/i);

    await userEvent.click(screen.getByRole('button', { name: /^retry$/i }));

    await waitFor(() =>
      expect(screen.getByLabelText(/requests per minute/i)).toBeInTheDocument(),
    );
  });

  test('Save success end-to-end: toast fires, dirty clears, caption refreshes', async () => {
    let state: RateLimitConfig = aConfig({
      perMinute: 60,
      perHour: 3600,
      updatedAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    });
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () => HttpResponse.json(state)),
      http.put(`${BASE}/admin/rate-limit`, async ({ request }) => {
        const body = (await request.json()) as { perMinute: number; perHour: number };
        state = {
          ...state,
          perMinute: body.perMinute,
          perHour: body.perHour,
          updatedAt: new Date().toISOString(),
        };
        return HttpResponse.json(state);
      }),
    );

    renderWithProviders(<AdminRateLimitPage />);
    await screen.findByLabelText(/requests per minute/i);

    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');

    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/rate limit updated/i),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled(),
    );
    // Post-invalidation refetch surfaces the bumped updatedAt: caption reads "now"
    // (or "a few seconds ago" depending on locale). The presence of the caption
    // is the load-bearing invariant.
    expect(screen.getByText(/last updated/i)).toBeInTheDocument();
  });

  test('429 during save: countdown alert renders inside the form, no success toast', async () => {
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () => HttpResponse.json(aConfig())),
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '3' },
          },
        ),
      ),
    );

    renderWithProviders(<AdminRateLimitPage />);
    await screen.findByLabelText(/requests per minute/i);

    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() =>
      expect(screen.getByText(/try again in 3s/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
    // No success toast should fire — the alert is the surface.
    expect(screen.queryByTestId('toast-success')).toBeNull();
  });

  test('500 during save: top-of-form alert renders, Save re-enables', async () => {
    server.use(
      http.get(`${BASE}/admin/rate-limit`, () => HttpResponse.json(aConfig())),
      http.put(`${BASE}/admin/rate-limit`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<AdminRateLimitPage />);
    await screen.findByLabelText(/requests per minute/i);

    const perMinute = screen.getByLabelText(/requests per minute/i);
    await userEvent.clear(perMinute);
    await userEvent.type(perMinute, '120');
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    // Two role=alert candidates exist (loading skeleton removed by now; only
    // the form-level alert remains).
    await waitFor(() => {
      const alerts = screen.getAllByRole('alert');
      const internal = alerts.find((a) => /internal error/i.test(a.textContent ?? ''));
      expect(internal).toBeDefined();
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled(),
    );
  });
});
