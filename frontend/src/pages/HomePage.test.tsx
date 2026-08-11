import { describe, expect, test } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { env } from '@/env';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/render';
import type { TokenBundle } from '@/shared/auth/tokenStorage';
import HomePage from './HomePage';

const BASE = env.VITE_API_BASE_URL;

function base64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeBundle(role: 'ADMIN' | 'STANDARD' = 'STANDARD'): TokenBundle {
  const jwt = [
    base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64url(
      JSON.stringify({
        sub: 'alice@example.com',
        role,
        iat: Math.floor(Date.now() / 1000),
        jti: 'jti-home',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ),
    'sig',
  ].join('.');
  return {
    token: jwt,
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    mustChangePassword: false,
  };
}

function stubAllCatalogs() {
  server.use(
    http.get(`${BASE}/agents`, () =>
      HttpResponse.json({
        items: [
          {
            id: '11111111-1111-1111-1111-111111111111',
            ownerId: '22222222-2222-2222-2222-222222222222',
            name: 'writer',
            description: 'writes',
            systemPrompt: 'be helpful',
            memorySize: 12,
            tools: [],
            enabledMcpServers: [],
            team: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-06-01T00:00:00Z',
          },
        ],
        nextCursor: null,
        pageSize: 20,
      }),
    ),
    http.get(`${BASE}/conversations`, () =>
      HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
    ),
    http.get(`${BASE}/tools`, () =>
      HttpResponse.json({
        items: [{ name: 'awsS3', description: 'S3 tool' }],
      }),
    ),
    http.get(`${BASE}/mcp-servers`, () =>
      HttpResponse.json({ items: [] }),
    ),
  );
}

describe('HomePage (dashboard)', () => {
  test('renders the four workspace cards with links to the corresponding pages', async () => {
    stubAllCatalogs();
    renderWithProviders(<HomePage />, { initialBundle: makeBundle('STANDARD') });

    expect(
      screen.getByRole('heading', { level: 2, name: /workspace/i }),
    ).toBeInTheDocument();

    expect(screen.getByRole('link', { name: /agents.*manage agents/i })).toHaveAttribute(
      'href',
      '/agents',
    );
    expect(screen.getByRole('link', { name: /chats.*open chat/i })).toHaveAttribute(
      'href',
      '/chat',
    );
    expect(screen.getByRole('link', { name: /tools.*browse tools/i })).toHaveAttribute(
      'href',
      '/tools',
    );
    expect(
      screen.getByRole('link', { name: /mcp servers.*browse mcp servers/i }),
    ).toHaveAttribute('href', '/mcp-servers');

    // Populated preview surfaces at least one item.
    await waitFor(() => expect(screen.getByText('writer')).toBeInTheDocument());
  });

  test('hides the Administration section for STANDARD users', async () => {
    stubAllCatalogs();
    renderWithProviders(<HomePage />, { initialBundle: makeBundle('STANDARD') });

    expect(
      screen.queryByRole('heading', { level: 2, name: /administration/i }),
    ).not.toBeInTheDocument();
  });

  test('reveals the Administration section for ADMIN users', async () => {
    stubAllCatalogs();
    renderWithProviders(<HomePage />, { initialBundle: makeBundle('ADMIN') });

    expect(
      screen.getByRole('heading', { level: 2, name: /administration/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /users.*manage platform accounts/i })).toHaveAttribute(
      'href',
      '/admin/users',
    );
    expect(
      screen.getByRole('link', { name: /api keys.*machine-to-machine credentials/i }),
    ).toHaveAttribute('href', '/admin/api-keys');
    expect(
      screen.getByRole('link', { name: /rate limit.*live throttling configuration/i }),
    ).toHaveAttribute('href', '/admin/rate-limit');
  });

  test('shows an empty-state hint on cards whose lists are empty', async () => {
    server.use(
      http.get(`${BASE}/agents`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/conversations`, () =>
        HttpResponse.json({ items: [], nextCursor: null, pageSize: 20 }),
      ),
      http.get(`${BASE}/tools`, () => HttpResponse.json({ items: [] })),
      http.get(`${BASE}/mcp-servers`, () => HttpResponse.json({ items: [] })),
    );
    renderWithProviders(<HomePage />, { initialBundle: makeBundle('STANDARD') });

    await waitFor(() =>
      expect(screen.getByText(/no agents yet/i)).toBeInTheDocument(),
    );
    expect(screen.getByText(/no conversations yet/i)).toBeInTheDocument();
    expect(screen.getByText(/no tools are configured/i)).toBeInTheDocument();
    expect(screen.getByText(/no mcp servers configured/i)).toBeInTheDocument();
  });
});
