import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '@/test/server';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { env } from '@/env';
import { qk } from '@/shared/api/queryKeys';
import {
  useAgent,
  useAgents,
  useCreateAgent,
  useDeleteAgent,
  useUpdateAgent,
} from './api';
import type { Agent } from './schema';

const BASE = env.VITE_API_BASE_URL;

function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Researcher',
    description: 'Reads docs.',
    systemPrompt: 'You are a researcher.',
    memorySize: 12,
    tools: [],
    enabledMcpServers: [],
    team: [],
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

describe('useAgents', () => {
  test('200 happy path: first page exposed and fetchNextPage drains cursor', async () => {
    server.use(
      http.get(`${BASE}/agents`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [anAgent({ id: '11111111-1111-1111-1111-111111111111', name: 'A' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [anAgent({ id: '22222222-2222-2222-2222-222222222222', name: 'B' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    const { result } = renderHook(() => useAgents(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('useAgent', () => {
  test('200 happy path: returns the Agent', async () => {
    const agent = anAgent({ id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1' });
    server.use(
      http.get(`${BASE}/agents/:agentId`, ({ params }) => {
        expect(params.agentId).toBe(agent.id);
        return HttpResponse.json(agent);
      }),
    );

    const { result } = renderHook(() => useAgent(agent.id), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe(agent.id);
  });

  test('undefined agentId: query is disabled, no network call fires', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/agents/:agentId`, () => {
        calls += 1;
        return HttpResponse.json(anAgent());
      }),
    );

    const { result } = renderHook(() => useAgent(undefined), {
      wrapper: wrapperFor(freshClient()),
    });
    // Give the query machinery a tick; it must NOT fire.
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
    expect(result.current.isPending).toBe(true);
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateAgent', () => {
  test('201 happy path: invalidates the agents list cache', async () => {
    const agent = anAgent({ name: 'New One' });
    server.use(http.post(`${BASE}/agents`, () => HttpResponse.json(agent, { status: 201 })));

    const client = freshClient();
    // Seed the list cache so we can verify invalidation.
    client.setQueryData(qk.agents.list(), { pageParams: [undefined], pages: [{ items: [], nextCursor: null, pageSize: 20 }] });

    const { result } = renderHook(() => useCreateAgent(), { wrapper: wrapperFor(client) });
    result.current.mutate({
      name: 'New One',
      description: 'd',
      systemPrompt: 's',
      memorySize: 12,
      tools: [],
      enabledMcpServers: [],
      team: [],
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.name).toBe('New One');

    // The list-cache entry is now stale (invalidated). The query state
    // reports it as stale.
    const state = client.getQueryState(qk.agents.list());
    expect(state?.isInvalidated).toBe(true);
  });

  test('409 DUPLICATE_AGENT_NAME: error surfaced, cache not invalidated', async () => {
    server.use(
      http.post(`${BASE}/agents`, () =>
        HttpResponse.json(
          {
            title: 'Duplicate agent name',
            status: 409,
            code: 'DUPLICATE_AGENT_NAME',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.agents.list(), { pageParams: [undefined], pages: [{ items: [], nextCursor: null, pageSize: 20 }] });

    const { result } = renderHook(() => useCreateAgent(), { wrapper: wrapperFor(client) });
    result.current.mutate({
      name: 'dupe',
      description: 'd',
      systemPrompt: 's',
      memorySize: 12,
      tools: [],
      enabledMcpServers: [],
      team: [],
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('DUPLICATE_AGENT_NAME');

    const state = client.getQueryState(qk.agents.list());
    expect(state?.isInvalidated).toBe(false);
  });
});

describe('useUpdateAgent', () => {
  test('200 happy path: invalidates the detail cache for that id', async () => {
    const agent = anAgent({ name: 'Updated' });
    server.use(http.put(`${BASE}/agents/:agentId`, () => HttpResponse.json(agent)));

    const client = freshClient();
    client.setQueryData(qk.agents.byId(agent.id), anAgent({ name: 'Old' }));

    const { result } = renderHook(() => useUpdateAgent(agent.id), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({
      name: 'Updated',
      description: 'd',
      systemPrompt: 's',
      memorySize: 12,
      tools: [],
      enabledMcpServers: [],
      team: [],
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = client.getQueryState(qk.agents.byId(agent.id));
    expect(state?.isInvalidated).toBe(true);
  });
});

describe('useDeleteAgent', () => {
  test('204 happy path: invalidates the agents list', async () => {
    const agentId = '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1';
    server.use(
      http.delete(`${BASE}/agents/:agentId`, () => new HttpResponse(null, { status: 204 })),
    );

    const client = freshClient();
    client.setQueryData(qk.agents.list(), { pageParams: [undefined], pages: [{ items: [], nextCursor: null, pageSize: 20 }] });

    const { result } = renderHook(() => useDeleteAgent(), { wrapper: wrapperFor(client) });
    result.current.mutate({ agentId });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = client.getQueryState(qk.agents.list());
    expect(state?.isInvalidated).toBe(true);
  });
});
