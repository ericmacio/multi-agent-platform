import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { CreateApiKeyDialog } from './CreateApiKeyDialog';
import type { ApiKey, ApiKeyCreated } from './schema';

const BASE = env.VITE_API_BASE_URL;

const CREATED: ApiKeyCreated = {
  clientId: 'cid-new',
  label: 'CI',
  disabled: false,
  createdAt: '2026-01-01T00:00:00Z',
  apiKey: 'sk_live_supersecret_1234567890',
};

function stubClipboard(): () => void {
  const original = navigator.clipboard;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn(() => Promise.resolve()) },
  });
  return () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: original,
    });
  };
}

function Harness({
  onCreated,
  onClose,
  initialOpen = true,
}: {
  onCreated?: (k: ApiKey) => void;
  onClose?: () => void;
  initialOpen?: boolean;
}) {
  const [open, setOpen] = useState(initialOpen);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        open-harness
      </button>
      <CreateApiKeyDialog
        open={open}
        onClose={() => {
          setOpen(false);
          onClose?.();
        }}
        onCreated={onCreated}
      />
    </>
  );
}

let restoreClipboard: (() => void) | null = null;

beforeEach(() => {
  tokenStorage.clear();
  restoreClipboard = stubClipboard();
});
afterEach(() => {
  tokenStorage.clear();
  restoreClipboard?.();
  restoreClipboard = null;
});

describe('CreateApiKeyDialog', () => {
  test('Phase 1 empty submit succeeds and pivots to Phase 2', async () => {
    let receivedBody: unknown = null;
    server.use(
      http.post(`${BASE}/admin/api-keys`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(CREATED, { status: 201 });
      }),
    );

    renderWithProviders(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());
    // Backend accepts an empty body / no label.
    expect(receivedBody).toEqual({});
    expect(screen.getByLabelText(/^api key$/i)).toHaveValue(CREATED.apiKey);
  });

  test('Phase 1 with label: sends { label }, pivots to Phase 2 with cleartext', async () => {
    let receivedBody: unknown = null;
    server.use(
      http.post(`${BASE}/admin/api-keys`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(CREATED, { status: 201 });
      }),
    );

    renderWithProviders(<Harness />);
    await userEvent.type(screen.getByLabelText(/^label$/i), 'CI');
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());
    expect(receivedBody).toEqual({ label: 'CI' });
    expect(screen.getByLabelText(/^api key$/i)).toHaveValue(CREATED.apiKey);
  });

  test('Phase 2: Client ID caption visible in mono', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(CREATED, { status: 201 })),
    );

    renderWithProviders(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());
    expect(screen.getByText(/^Client ID:$/i)).toBeInTheDocument();
    expect(screen.getByText(CREATED.clientId)).toBeInTheDocument();
  });

  test('Phase 2 Done: onCreated is called without apiKey, dialog closes, cleartext gone', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(CREATED, { status: 201 })),
    );

    const onCreated = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<Harness onCreated={onCreated} onClose={onClose} />);

    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));
    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    await userEvent.click(screen.getByRole('button', { name: /^done$/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onCreated).toHaveBeenCalledTimes(1);
    const stripped = onCreated.mock.calls[0]?.[0];
    expect(stripped).toEqual({
      clientId: CREATED.clientId,
      label: CREATED.label,
      disabled: CREATED.disabled,
      createdAt: CREATED.createdAt,
    });
    // The stripped payload has NO apiKey field.
    expect((stripped as Record<string, unknown>).apiKey).toBeUndefined();

    // After close, the cleartext string is no longer in the DOM.
    expect(screen.queryByDisplayValue(CREATED.apiKey)).toBeNull();
  });

  test('Phase 2 Esc-close does NOT close the dialog', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(CREATED, { status: 201 })),
    );

    const onClose = vi.fn();
    renderWithProviders(<Harness onClose={onClose} />);

    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));
    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());

    await userEvent.keyboard('{Escape}');
    // Escape should NOT close in Phase 2.
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByText(/API key created/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^api key$/i)).toHaveValue(CREATED.apiKey);
  });

  test('400 VALIDATION_ERROR on label surfaces per-field, dialog stays in Phase 1', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json(
          {
            title: 'Validation error',
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'label', message: 'Label is too long.' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<Harness />);
    await userEvent.type(screen.getByLabelText(/^label$/i), 'CI');
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() =>
      expect(screen.getByText(/label is too long/i)).toBeInTheDocument(),
    );
    // Still Phase 1 — no "API key created" header.
    expect(screen.queryByText(/API key created/i)).not.toBeInTheDocument();
  });

  test('429 RATE_LIMITED with Retry-After: countdown alert renders', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: {
              'Content-Type': 'application/problem+json',
              'Retry-After': '5',
            },
          },
        ),
      ),
    );

    renderWithProviders(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() =>
      expect(screen.getByText(/too many requests\. try again in 5s/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /^create$/i })).toBeDisabled();
  });

  test('500 INTERNAL_ERROR: top-of-form alert renders, Create re-enables', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () =>
        HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /^create$/i })).toBeEnabled();
  });

  test('Cancel during Phase 1: onClose fires, no network call was made', async () => {
    let calls = 0;
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => {
        calls += 1;
        return HttpResponse.json(CREATED, { status: 201 });
      }),
    );

    const onClose = vi.fn();
    renderWithProviders(<Harness onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: /^cancel$/i }));

    expect(onClose).toHaveBeenCalled();
    expect(calls).toBe(0);
  });

  test('Reset on close-then-reopen: no prior key cleartext leaks across sessions', async () => {
    server.use(
      http.post(`${BASE}/admin/api-keys`, () => HttpResponse.json(CREATED, { status: 201 })),
    );

    renderWithProviders(<Harness />);

    // Complete a first cycle: create → Copy → Done → close.
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));
    await waitFor(() => expect(screen.getByText(/API key created/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    await userEvent.click(screen.getByRole('button', { name: /^done$/i }));

    await waitFor(() => expect(screen.queryByDisplayValue(CREATED.apiKey)).toBeNull());

    // Reopen the dialog — Phase 1 should be pristine.
    await userEvent.click(screen.getByRole('button', { name: /open-harness/i }));

    expect(screen.getByRole('button', { name: /^create$/i })).toBeInTheDocument();
    expect(screen.queryByDisplayValue(CREATED.apiKey)).toBeNull();
    const labelInput = screen.getByLabelText(/^label$/i) as HTMLInputElement;
    expect(labelInput.value).toBe('');
  });
});
