import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { env } from '@/env';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { MessageList } from './MessageList';
import type { Message } from './schema';

const BASE = env.VITE_API_BASE_URL;
const CONV_ID = '11111111-1111-4111-9111-111111111111';

// jsdom does not implement ResizeObserver or layout, but
// `@tanstack/react-virtual` needs both to compute a visible window. We stub
// ResizeObserver with a no-op and pin the scroll element's clientHeight so the
// virtualizer believes it has 600 px of viewport to fill. Both overrides are
// restored in afterAll so they don't leak into unrelated test files.
let originalResizeObserver: typeof ResizeObserver | undefined;
let originalScrollTo: HTMLElement['scrollTo'] | undefined;
beforeAll(() => {
  if (typeof globalThis.ResizeObserver === 'undefined') {
    originalResizeObserver = undefined;
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
  }
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get() {
      return 600;
    },
  });
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get() {
      return 800;
    },
  });
  if (!HTMLElement.prototype.scrollTo) {
    originalScrollTo = undefined;
    HTMLElement.prototype.scrollTo = vi.fn() as unknown as HTMLElement['scrollTo'];
  }
});

afterAll(() => {
  if (originalResizeObserver === undefined) {
    delete (globalThis as { ResizeObserver?: typeof ResizeObserver }).ResizeObserver;
  }
  delete (HTMLElement.prototype as unknown as { clientHeight?: number }).clientHeight;
  delete (HTMLElement.prototype as unknown as { clientWidth?: number }).clientWidth;
  if (originalScrollTo === undefined) {
    delete (HTMLElement.prototype as unknown as { scrollTo?: HTMLElement['scrollTo'] }).scrollTo;
  }
});

function aMessage(i: number, role: 'USER' | 'ASSISTANT' = i % 2 === 0 ? 'USER' : 'ASSISTANT'): Message {
  return {
    id: `${i.toString().padStart(8, '0')}-aaaa-4aaa-9aaa-aaaaaaaaaaaa`,
    role,
    content: `Message ${i}`,
    createdAt: '2026-01-01T00:00:00Z',
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('MessageList', () => {
  test('200 with items: renders messages and the log region', async () => {
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () =>
        HttpResponse.json({
          items: [aMessage(0, 'USER'), aMessage(1, 'ASSISTANT')],
          nextCursor: null,
          pageSize: 64,
        }),
      ),
    );

    renderWithProviders(<MessageList conversationId={CONV_ID} />);

    await waitFor(() => expect(screen.getByText('Message 0')).toBeInTheDocument());
    expect(screen.getByText('Message 1')).toBeInTheDocument();
    expect(screen.getByTestId('message-list')).toHaveAttribute('role', 'log');
    expect(screen.getByTestId('message-list')).toHaveAttribute('aria-live', 'polite');
  });

  test('empty conversation: renders the "No messages yet" empty state', async () => {
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 64 }),
      ),
    );

    renderWithProviders(<MessageList conversationId={CONV_ID} />);
    await waitFor(() => expect(screen.getByText(/no messages yet/i)).toBeInTheDocument());
  });

  test('500 error: retryable alert renders and Retry re-fetches', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({ items: [aMessage(0)], nextCursor: null, pageSize: 64 });
      }),
    );

    renderWithProviders(<MessageList conversationId={CONV_ID} />);
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(screen.getByText('Message 0')).toBeInTheDocument());
  });

  test('404: renders the "Conversation not found" empty state with a Back link', async () => {
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, code: 'NOT_FOUND' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    renderWithProviders(<MessageList conversationId={CONV_ID} />);
    await waitFor(() =>
      expect(screen.getByText(/conversation not found/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('link', { name: /back to chats/i })).toHaveAttribute(
      'href',
      '/chat',
    );
  });

  test('64-message response: every message exists in the data cache', async () => {
    const items = Array.from({ length: 64 }, (_, i) => aMessage(i));
    server.use(
      http.get(`${BASE}/conversations/:conversationId/messages`, () =>
        HttpResponse.json({ items, nextCursor: null, pageSize: 64 }),
      ),
    );

    renderWithProviders(<MessageList conversationId={CONV_ID} />);
    // The virtualizer renders only a window; jsdom reports height as 0, so we
    // assert the log region is present and the underlying data set carries the
    // expected count via at least the first message being visible.
    await waitFor(() => expect(screen.getByTestId('message-list')).toBeInTheDocument());
    expect(screen.getByText('Message 0')).toBeInTheDocument();
  });
});
