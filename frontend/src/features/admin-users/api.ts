import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
  type UseInfiniteQueryResult,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import { useCursorInfiniteQuery, type PageEnvelope } from '@/shared/lib/pagination';
import type { CreateUserRequest, UpdateUserRequest, User } from './schema';

const DEFAULT_PAGE_SIZE = 20;

type InfiniteListCache = {
  pages: PageEnvelope<User>[];
  pageParams: (string | undefined)[];
};

/**
 * `GET /admin/users` — admin-only, cursor-paginated list of users. Default
 * page size 20 per openapi. Caller flattens via `flattenPages()`.
 */
export function useUsers(opts?: {
  pageSize?: number;
}): UseInfiniteQueryResult<InfiniteListCache, ApiError> {
  const pageSize = opts?.pageSize ?? DEFAULT_PAGE_SIZE;
  return useCursorInfiniteQuery<User>({
    queryKey: qk.admin.users.list(),
    fetchPage: async (cursor) => {
      const data = await unwrap(
        await api.GET('/admin/users', {
          params: { query: { cursor, pageSize } },
        }),
      );
      return {
        items: data?.items ?? [],
        nextCursor: data?.nextCursor ?? null,
        pageSize: data?.pageSize ?? pageSize,
      };
    },
  });
}

/**
 * `GET /admin/users/{userId}` — disabled when `userId` is falsy so route
 * mounts that haven't resolved the param yet stay quiet.
 */
export function useUser(userId: string | undefined): UseQueryResult<User, ApiError> {
  return useQuery<User, ApiError>({
    queryKey: qk.admin.users.byId(userId ?? ''),
    queryFn: async () => {
      const data = await unwrap(
        await api.GET('/admin/users/{userId}', {
          params: { path: { userId: userId! } },
        }),
      );
      return data!;
    },
    enabled: Boolean(userId),
  });
}

/**
 * `POST /admin/users` — invalidates the users list on success so the next
 * list read re-fetches with the new entry. The detail cache is not
 * pre-seeded; a clean refetch keeps the cache honest.
 */
export function useCreateUser(): UseMutationResult<User, ApiError, CreateUserRequest> {
  const queryClient = useQueryClient();
  return useMutation<User, ApiError, CreateUserRequest>({
    mutationFn: async (body) => {
      const data = await unwrap(await api.POST('/admin/users', { body }));
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.users.all() });
    },
  });
}

type UpdateSnapshot = {
  byId: User | undefined;
  lists: Array<{ key: QueryKey; data: InfiniteListCache | undefined }>;
};

function patchListWithDisabled(
  cache: InfiniteListCache | undefined,
  userId: string,
  disabled: boolean,
  updatedAt: string,
): InfiniteListCache | undefined {
  if (!cache) return cache;
  return {
    pageParams: cache.pageParams,
    pages: cache.pages.map((p) => ({
      ...p,
      items: p.items.map((u) => (u.id === userId ? { ...u, disabled, updatedAt } : u)),
    })),
  };
}

/**
 * `PATCH /admin/users/{userId}` — optimistic `disabled` toggle. The
 * detail-cache and every list-cache variant flip immediately on `onMutate`.
 * On error the snapshot is restored so the row's badge and any detail-page
 * CTA label revert together. `onSettled` invalidates so the server's
 * `updatedAt` wins on the next tick.
 */
export function useUpdateUser(
  userId: string,
): UseMutationResult<User, ApiError, UpdateUserRequest, UpdateSnapshot> {
  const queryClient = useQueryClient();
  return useMutation<User, ApiError, UpdateUserRequest, UpdateSnapshot>({
    mutationFn: async (body) => {
      const data = await unwrap(
        await api.PATCH('/admin/users/{userId}', {
          params: { path: { userId } },
          body,
        }),
      );
      return data!;
    },
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: qk.admin.users.all() });

      const now = new Date().toISOString();
      const byId = queryClient.getQueryData<User>(qk.admin.users.byId(userId));
      if (byId && body.disabled !== undefined) {
        queryClient.setQueryData<User>(qk.admin.users.byId(userId), {
          ...byId,
          disabled: body.disabled,
          updatedAt: now,
        });
      }

      const listEntries = queryClient.getQueriesData<InfiniteListCache>({
        queryKey: qk.admin.users.all(),
      });
      const lists: UpdateSnapshot['lists'] = [];
      for (const [key, data] of listEntries) {
        if (!Array.isArray(key) || key[2] !== 'list') continue;
        lists.push({ key, data });
        if (body.disabled !== undefined) {
          queryClient.setQueryData<InfiniteListCache>(
            key,
            patchListWithDisabled(data, userId, body.disabled, now),
          );
        }
      }

      return { byId, lists };
    },
    onError: (_err, _vars, context) => {
      if (!context) return;
      if (context.byId) {
        queryClient.setQueryData<User>(qk.admin.users.byId(userId), context.byId);
      }
      for (const { key, data } of context.lists) {
        queryClient.setQueryData<InfiniteListCache>(key, data);
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.users.all() });
      void queryClient.invalidateQueries({ queryKey: qk.admin.users.byId(userId) });
    },
  });
}

/**
 * `DELETE /admin/users/{userId}` — hard delete, cascades to the user's
 * agents and conversations on the backend (`REQ-USR-006`). Not optimistic:
 * `DeleteUserDialog` (US-08-003) already pauses the admin explicitly.
 */
export function useDeleteUser(): UseMutationResult<void, ApiError, { userId: string }> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, { userId: string }>({
    mutationFn: async ({ userId }) => {
      await api.DELETE('/admin/users/{userId}', {
        params: { path: { userId } },
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.users.all() });
    },
  });
}
