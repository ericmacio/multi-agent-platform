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
  useCreateUser,
  useDeleteUser,
  useUpdateUser,
  useUser,
  useUsers,
} from './api';
import type { User } from './schema';

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

function aUser(overrides: Partial<User> = {}): User {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    email: 'alice@example.com',
    role: 'STANDARD',
    disabled: false,
    mustChangePassword: false,
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

describe('useUsers', () => {
  test('200 happy path: first page exposed and fetchNextPage drains cursor', async () => {
    server.use(
      http.get(`${BASE}/admin/users`, ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        if (cursor === null) {
          return HttpResponse.json({
            items: [aUser({ id: '11111111-1111-1111-1111-111111111111', email: 'a@x.io' })],
            nextCursor: 'c2',
            pageSize: 20,
          });
        }
        return HttpResponse.json({
          items: [aUser({ id: '22222222-2222-2222-2222-222222222222', email: 'b@x.io' })],
          nextCursor: null,
          pageSize: 20,
        });
      }),
    );

    const { result } = renderHook(() => useUsers(), { wrapper: wrapperFor(freshClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('useUser', () => {
  test('200 happy path: returns the User', async () => {
    const user = aUser({ id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1' });
    server.use(
      http.get(`${BASE}/admin/users/:userId`, ({ params }) => {
        expect(params.userId).toBe(user.id);
        return HttpResponse.json(user);
      }),
    );

    const { result } = renderHook(() => useUser(user.id), {
      wrapper: wrapperFor(freshClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe(user.id);
  });

  test('undefined userId: query is disabled, no network call fires', async () => {
    let calls = 0;
    server.use(
      http.get(`${BASE}/admin/users/:userId`, () => {
        calls += 1;
        return HttpResponse.json(aUser());
      }),
    );

    const { result } = renderHook(() => useUser(undefined), {
      wrapper: wrapperFor(freshClient()),
    });
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
    expect(result.current.isPending).toBe(true);
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateUser', () => {
  test('201 happy path: invalidates the users list cache', async () => {
    const user = aUser({ email: 'new@example.com' });
    server.use(
      http.post(`${BASE}/admin/users`, () => HttpResponse.json(user, { status: 201 })),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.users.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useCreateUser(), { wrapper: wrapperFor(client) });
    result.current.mutate({
      email: 'new@example.com',
      password: 'AValid!Pw1',
      role: 'STANDARD',
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.email).toBe('new@example.com');

    const state = client.getQueryState(qk.admin.users.list());
    expect(state?.isInvalidated).toBe(true);
  });

  test('409 CONFLICT: error surfaced, cache not invalidated', async () => {
    server.use(
      http.post(`${BASE}/admin/users`, () =>
        HttpResponse.json(
          {
            title: 'Conflict',
            status: 409,
            code: 'CONFLICT',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.users.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useCreateUser(), { wrapper: wrapperFor(client) });
    result.current.mutate({
      email: 'dupe@example.com',
      password: 'AValid!Pw1',
      role: 'STANDARD',
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('CONFLICT');

    const state = client.getQueryState(qk.admin.users.list());
    expect(state?.isInvalidated).toBe(false);
  });
});

describe('useUpdateUser', () => {
  test('optimistic disabled flip on the list cache; onSettled invalidates', async () => {
    const original = aUser({ id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1', disabled: false });
    server.use(
      http.patch(`${BASE}/admin/users/:userId`, () =>
        HttpResponse.json({ ...original, disabled: true, updatedAt: '2026-01-02T00:00:00Z' }),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.users.list(), {
      pageParams: [undefined],
      pages: [{ items: [original], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useUpdateUser(original.id), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ disabled: true });

    // Optimistic flip visible before the response resolves.
    await waitFor(() => {
      const cache = client.getQueryData<{ pages: { items: User[] }[] }>(qk.admin.users.list());
      expect(cache?.pages[0]?.items[0]?.disabled).toBe(true);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // onSettled invalidates the list.
    expect(client.getQueryState(qk.admin.users.list())?.isInvalidated).toBe(true);
  });

  test('optimistic flip on byId cache; rollback on 500', async () => {
    let releaseError: (() => void) | undefined;
    const errorReleased = new Promise<void>((resolve) => {
      releaseError = resolve;
    });
    server.use(
      http.patch(`${BASE}/admin/users/:userId`, async () => {
        await errorReleased;
        return HttpResponse.json(
          { title: 'Internal error', status: 500, code: 'INTERNAL_ERROR' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );

    const client = freshClient();
    const original = aUser({ disabled: false });
    client.setQueryData(qk.admin.users.byId(original.id), original);

    const { result } = renderHook(() => useUpdateUser(original.id), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ disabled: true });

    // In-flight: optimistic flip is visible in the detail cache.
    await waitFor(() => {
      expect(client.getQueryData<User>(qk.admin.users.byId(original.id))?.disabled).toBe(true);
    });

    releaseError?.();
    await waitFor(() => expect(result.current.isError).toBe(true));
    // Rollback restored the original disabled value.
    expect(client.getQueryData<User>(qk.admin.users.byId(original.id))?.disabled).toBe(false);
  });
});

describe('useDeleteUser', () => {
  test('204 happy path: invalidates the users list', async () => {
    const userId = '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1';
    server.use(
      http.delete(
        `${BASE}/admin/users/:userId`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    const client = freshClient();
    client.setQueryData(qk.admin.users.list(), {
      pageParams: [undefined],
      pages: [{ items: [], nextCursor: null, pageSize: 20 }],
    });

    const { result } = renderHook(() => useDeleteUser(), { wrapper: wrapperFor(client) });
    result.current.mutate({ userId });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(client.getQueryState(qk.admin.users.list())?.isInvalidated).toBe(true);
  });
});
