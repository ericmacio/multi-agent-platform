import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { _resetToasts } from '@/shared/ui/Toast';
import { _resetRateLimitToast } from '@/shared/ui/toastPolicy';
import AdminApiKeysPage from './AdminApiKeysPage';
import type { ApiKey, ApiKeyCreated } from '@/features/admin-api-keys/schema';

const BASE = env.VITE_API_BASE_URL;

function anApiKey(overrides: Partial<ApiKey> = {}): ApiKey {
  return {
    clientId: 'cid-1',
    label: 'CI',
    disabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function stubClipboard(): () => void {
  const original = navigator.clipboard;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: () => Promise.resolve() },
  });
  return () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: original });
  };
}

let restoreClipboard: (() => void) | null = null;

beforeEach(() => {
  tokenStorage.clear();
  _resetToasts();
  _resetRateLimitToast();
  restoreClipboard = stubClipboard();
});
afterEach(() => {
  tokenStorage.clear();
  _resetToasts();
  _resetRateLimitToast();
  restoreClipboard?.();
  restoreClipboard = null;
});

describe('AdminApiKeysPage', () => {
  test('populated list renders + Create API key CTA opens the dialog', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({
          items: [
            anApiKey({ clientId: 'cid-a', label: 'CI', disabled: false }),
            anApiKey({ clientId: 'cid-b', label: null, disabled: true }),
            anApiKey({ clientId: 'cid-c', label: 'Legacy', disabled: false }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<AdminApiKeysPage />);
    expect(await screen.findByText('cid-a')).toBeInTheDocument();
    expect(screen.getByText('cid-b')).toBeInTheDocument();
    expect(screen.getByText('cid-c')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^create api key$/i }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  test('empty state renders + CTA opens the dialog', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    renderWithProviders(<AdminApiKeysPage />);
    expect(await screen.findByText(/no api keys yet/i)).toBeInTheDocument();

    const ctas = screen.getAllByRole('button', { name: /^create api key$/i });
    await userEvent.click(ctas[ctas.length - 1]!);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  test('create flow: dialog → Phase 2 reveal → Done wipes cleartext → toast + list refetch', async () => {
    const CREATED: ApiKeyCreated = {
      clientId: 'cid-new',
      label: 'CI',
      disabled: false,
      createdAt: '2026-02-01T00:00:00Z',
      apiKey: 'sk_live_supersecret_1234567890',
    };
    let listCalls = 0;
    server.use(
      http.get(`${BASE}/admin/api-keys`, () => {
        listCalls += 1;
        if (listCalls === 1) {
          return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
        }
        return HttpResponse.json({
          items: [anApiKey({ clientId: CREATED.clientId, label: CREATED.label })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(CREATED, { status: 201 })),
    );

    renderWithProviders(<AdminApiKeysPage />);
    await screen.findByText(/no api keys yet/i);

    // Open dialog from the empty-state CTA (bottom of the DOM).
    const ctas = screen.getAllByRole('button', { name: /^create api key$/i });
    await userEvent.click(ctas[ctas.length - 1]!);

    // Phase 1: type label + Create.
    await userEvent.type(screen.getByLabelText(/^label$/i), 'CI');
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    // Phase 2: reveal-once banner appears.
    await waitFor(() => expect(screen.getByText(/api key created/i)).toBeInTheDocument());
    expect(screen.getByLabelText(/^api key$/i)).toHaveValue(CREATED.apiKey);

    // Copy → Done → dialog closes.
    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    await userEvent.click(screen.getByRole('button', { name: /^done$/i }));

    // Success toast + reveal-once integrity (no cleartext anywhere in DOM).
    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/api key created/i),
    );
    expect(screen.queryByDisplayValue(CREATED.apiKey)).toBeNull();

    // List refetch surfaces the new row.
    await waitFor(() => expect(screen.getByText('cid-new')).toBeInTheDocument());
  });

  test('optimistic revoke flow: badge flips immediately + success toast', async () => {
    const state = { key: anApiKey({ clientId: 'cid-1', disabled: false }) };
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({ items: [state.key], nextCursor: null, pageSize: 20 }),
      ),
      http.patch(`${BASE}/admin/api-keys/:clientId`, async ({ request }) => {
        const body = (await request.json()) as { disabled: boolean };
        state.key = { ...state.key, disabled: body.disabled };
        return HttpResponse.json(state.key);
      }),
    );

    renderWithProviders(<AdminApiKeysPage />);
    await screen.findByText('cid-1');

    await userEvent.click(screen.getByRole('button', { name: /actions for cid-1/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /^revoke$/i }));

    // Optimistic flip: badge switches to Revoked before the response settles.
    await waitFor(() => expect(screen.getByText('Revoked')).toBeInTheDocument());
    // Success toast surfaces.
    await waitFor(() =>
      expect(screen.getByTestId('toast-success')).toHaveTextContent(/api key revoked/i),
    );
  });

  test('revoke rollback on 500: badge reverts + error toast', async () => {
    const original = anApiKey({ clientId: 'cid-1', disabled: false });
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({ items: [original], nextCursor: null, pageSize: 20 }),
      ),
      http.patch(`${BASE}/admin/api-keys/:clientId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    renderWithProviders(<AdminApiKeysPage />);
    await screen.findByText('cid-1');

    await userEvent.click(screen.getByRole('button', { name: /actions for cid-1/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /^revoke$/i }));

    // Optimistic flip visible.
    await waitFor(() => expect(screen.getByText('Revoked')).toBeInTheDocument());

    releaseError?.();

    // Rollback: badge reverts to Active.
    await waitFor(() => expect(screen.getByText('Active')).toBeInTheDocument());
    await waitFor(() =>
      expect(screen.getByTestId('toast-error')).toHaveTextContent(/internal error/i),
    );
  });
});
