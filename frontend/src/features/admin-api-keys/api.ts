import {
  useMutation,
  useQueryClient,
  type QueryKey,
  type UseInfiniteQueryResult,
  type UseMutationResult,
} from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import { useCursorInfiniteQuery, type PageEnvelope } from '@/shared/lib/pagination';
import type {
  ApiKey,
  ApiKeyCreated,
  CreateApiKeyRequest,
  UpdateApiKeyRequest,
} from './schema';

const DEFAULT_PAGE_SIZE = 20;

type InfiniteListCache = {
  pages: PageEnvelope<ApiKey>[];
  pageParams: (string | undefined)[];
};

/**
 * `GET /admin/api-keys` — admin-only, cursor-paginated list of API keys.
 * Caller flattens via `flattenPages()`. Default page size 20 per openapi.
 */
export function useApiKeys(opts?: {
  pageSize?: number;
}): UseInfiniteQueryResult<InfiniteListCache, ApiError> {
  const pageSize = opts?.pageSize ?? DEFAULT_PAGE_SIZE;
  return useCursorInfiniteQuery<ApiKey>({
    queryKey: qk.admin.apiKeys.list(),
    fetchPage: async (cursor) => {
      const data = await unwrap(
        await api.GET('/admin/api-keys', {
          params: { query: { cursor, pageSize } },
        }),
      );
      return {
        items: (data?.items ?? []) as ApiKey[],
        nextCursor: data?.nextCursor ?? null,
        pageSize: data?.pageSize ?? pageSize,
      };
    },
  });
}

/**
 * `POST /admin/api-keys` — invalidates the list on success so the next read
 * re-fetches with the new entry. Returns the full `ApiKeyCreated` payload
 * so `CreateApiKeyDialog` (US-09-003) can pivot to `RevealOnceBanner`
 * (US-09-002) with the cleartext in hand.
 */
export function useCreateApiKey(): UseMutationResult<
  ApiKeyCreated,
  ApiError,
  CreateApiKeyRequest
> {
  const queryClient = useQueryClient();
  return useMutation<ApiKeyCreated, ApiError, CreateApiKeyRequest>({
    mutationFn: async (body) => {
      const data = await unwrap(await api.POST('/admin/api-keys', { body }));
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.apiKeys.all() });
    },
  });
}

type UpdateSnapshot = {
  lists: Array<{ key: QueryKey; data: InfiniteListCache | undefined }>;
};

function patchListWithDisabled(
  cache: InfiniteListCache | undefined,
  clientId: string,
  disabled: boolean,
): InfiniteListCache | undefined {
  if (!cache) return cache;
  return {
    pageParams: cache.pageParams,
    pages: cache.pages.map((p) => ({
      ...p,
      items: p.items.map((k) => (k.clientId === clientId ? { ...k, disabled } : k)),
    })),
  };
}

/**
 * `PATCH /admin/api-keys/{clientId}` — optimistic `disabled` toggle. Every
 * list-cache variant flips immediately on `onMutate`; on error the snapshot
 * is restored; on settle the list is invalidated so the server truth wins
 * on the next tick.
 */
export function useUpdateApiKey(
  clientId: string,
): UseMutationResult<ApiKey, ApiError, UpdateApiKeyRequest, UpdateSnapshot> {
  const queryClient = useQueryClient();
  return useMutation<ApiKey, ApiError, UpdateApiKeyRequest, UpdateSnapshot>({
    mutationFn: async (body) => {
      const data = await unwrap(
        await api.PATCH('/admin/api-keys/{clientId}', {
          params: { path: { clientId } },
          body,
        }),
      );
      return data!;
    },
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: qk.admin.apiKeys.all() });

      const listEntries = queryClient.getQueriesData<InfiniteListCache>({
        queryKey: qk.admin.apiKeys.all(),
      });
      const lists: UpdateSnapshot['lists'] = [];
      for (const [key, data] of listEntries) {
        if (!Array.isArray(key) || key[2] !== 'list') continue;
        lists.push({ key, data });
        queryClient.setQueryData<InfiniteListCache>(
          key,
          patchListWithDisabled(data, clientId, body.disabled),
        );
      }

      return { lists };
    },
    onError: (_err, _vars, context) => {
      if (!context) return;
      for (const { key, data } of context.lists) {
        queryClient.setQueryData<InfiniteListCache>(key, data);
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.apiKeys.all() });
    },
  });
}
