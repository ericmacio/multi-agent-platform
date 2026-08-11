import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { renderWithProviders } from '@/test/render';
import ToolsPage from './ToolsPage';

const BASE = env.VITE_API_BASE_URL;

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('ToolsPage', () => {
  test('renders all rows from the catalog', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json({
          items: [
            { name: 'aws_s3', description: 'List/Get S3 objects.' },
            { name: 'http_fetch', description: 'Fetch a URL.' },
            { name: 'shell_exec', description: 'Run a shell command.' },
          ],
        }),
      ),
    );

    renderWithProviders(<ToolsPage />);

    expect(await screen.findByText('aws_s3')).toBeInTheDocument();
    expect(screen.getByText('http_fetch')).toBeInTheDocument();
    expect(screen.getByText('shell_exec')).toBeInTheDocument();
  });

  test('filter narrows by name OR description (case-insensitive)', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json({
          items: [
            { name: 'aws_s3', description: 'List/Get S3 objects.' },
            { name: 'http_fetch', description: 'Fetch a URL via HTTPS.' },
            { name: 'shell_exec', description: 'Run a shell command.' },
          ],
        }),
      ),
    );

    renderWithProviders(<ToolsPage />);

    const input = await screen.findByLabelText(/filter tools/i);
    await userEvent.type(input, 'HTTPS');

    expect(screen.queryByText('aws_s3')).not.toBeInTheDocument();
    expect(screen.getByText('http_fetch')).toBeInTheDocument();
    expect(screen.queryByText('shell_exec')).not.toBeInTheDocument();
  });

  test('empty-state when no match + Clear filter restores list', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json({ items: [{ name: 'aws_s3', description: 'desc' }] }),
      ),
    );

    renderWithProviders(<ToolsPage />);
    const input = await screen.findByLabelText(/filter tools/i);
    await userEvent.type(input, 'no-such-thing');

    expect(screen.getByText(/no tools match "no-such-thing"/i)).toBeInTheDocument();
    const clear = screen.getByRole('button', { name: /clear filter/i });
    await userEvent.click(clear);
    expect(await screen.findByText('aws_s3')).toBeInTheDocument();
  });

  test('server-side empty catalog renders "No tools configured" and hides the filter', async () => {
    server.use(http.get(`${BASE}/tools`, () => HttpResponse.json({ items: [] })));

    renderWithProviders(<ToolsPage />);
    expect(await screen.findByText(/no tools configured/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/filter tools/i)).not.toBeInTheDocument();
  });

  test('INTERNAL_ERROR surfaces as retryable; Retry recovers to success', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/tools`, () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({ items: [{ name: 'aws_s3', description: 'desc' }] });
      }),
    );

    renderWithProviders(<ToolsPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));

    await waitFor(() => expect(screen.getByText('aws_s3')).toBeInTheDocument());
  });
});
