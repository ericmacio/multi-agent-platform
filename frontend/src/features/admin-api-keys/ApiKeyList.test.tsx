import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { ApiKeyList } from './ApiKeyList';
import type { ApiKey } from './schema';

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

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('ApiKeyList', () => {
  test('renders columns: clientId (mono), label, badges, relative created', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({
          items: [
            anApiKey({ clientId: 'cid-active', label: 'CI', disabled: false }),
            anApiKey({ clientId: 'cid-revoked', label: 'Legacy', disabled: true }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);

    expect(await screen.findByText('cid-active')).toBeInTheDocument();
    expect(screen.getByText('cid-revoked')).toBeInTheDocument();
    expect(screen.getByText('CI')).toBeInTheDocument();
    expect(screen.getByText('Legacy')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Revoked')).toBeInTheDocument();
  });

  test('null label renders as em-dash', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({
          items: [anApiKey({ clientId: 'cid-null-label', label: null })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    await screen.findByText('cid-null-label');
    // The em-dash appears in the label cell.
    const row = screen.getByTestId('api-key-row-cid-null-label');
    expect(row).toHaveTextContent('—');
  });

  test('action menu: Revoke for active, Re-enable for disabled', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({
          items: [
            anApiKey({ clientId: 'cid-active', disabled: false }),
            anApiKey({ clientId: 'cid-revoked', disabled: true }),
          ],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    await screen.findByText('cid-active');

    await userEvent.click(screen.getByRole('button', { name: /actions for cid-active/i }));
    expect(screen.getByRole('menuitem', { name: /^revoke$/i })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    await userEvent.click(screen.getByRole('button', { name: /actions for cid-revoked/i }));
    expect(screen.getByRole('menuitem', { name: /^re-enable$/i })).toBeInTheDocument();
  });

  test('onToggleDisabled fires with the row apiKey', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({
          items: [anApiKey({ clientId: 'cid-1', disabled: false })],
          nextCursor: null,
          pageSize: 20,
        }),
      ),
    );

    const onToggleDisabled = vi.fn();
    renderWithProviders(<ApiKeyList onToggleDisabled={onToggleDisabled} />);
    await screen.findByText('cid-1');

    await userEvent.click(screen.getByRole('button', { name: /actions for cid-1/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /^revoke$/i }));

    expect(onToggleDisabled).toHaveBeenCalledTimes(1);
    expect(onToggleDisabled.mock.calls[0]?.[0]).toMatchObject({
      clientId: 'cid-1',
      disabled: false,
    });
  });

  test('Load more appends the next page', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [anApiKey({ clientId: 'cid-a' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [anApiKey({ clientId: 'cid-b' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    await screen.findByText('cid-a');
    await userEvent.click(screen.getByRole('button', { name: /load more/i }));
    await waitFor(() => expect(screen.getByText('cid-b')).toBeInTheDocument());
  });

  test('skeleton loading rows on first paint', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, async () => {
        // Never resolve during the assertion window — the skeleton should be visible.
        await new Promise((resolve) => setTimeout(resolve, 200));
        return HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 });
      }),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    expect(screen.getByTestId('api-key-list-loading')).toBeInTheDocument();
  });

  test('error + retry recovers on success', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/admin/api-keys`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({
          items: [anApiKey({ clientId: 'cid-recovered' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('cid-recovered')).toBeInTheDocument());
  });

  test('returns null when the flattened list is empty', async () => {
    server.use(
      http.get(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
    );

    const { container } = renderWithProviders(<ApiKeyList onToggleDisabled={vi.fn()} />);
    await waitFor(() =>
      expect(container.querySelector('[data-testid="api-key-list-loading"]')).toBeNull(),
    );
    expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
