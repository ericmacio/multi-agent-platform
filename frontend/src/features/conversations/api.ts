import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import { useCursorInfiniteQuery, type PageEnvelope } from '@/shared/lib/pagination';
import type { Conversation, Message, UpdateConversationRequest } from './schema';

const DEFAULT_PAGE_SIZE = 20;
const MESSAGES_PAGE_SIZE = 64;

type InfiniteListCache = {
  pages: PageEnvelope<Conversation>[];
  pageParams: (string | undefined)[];
};

/**
 * `GET /conversations` — cursor-paginated, optionally filtered by agent. Per
 * SW-DESIGN §7.6 the conversation list refetches on window focus (activity may
 * have changed `updatedAt`); we leave `staleTime` at the QueryClient default
 * (0) so a manual `invalidateQueries` after a mutation always re-fetches.
 */
export function useConversations(opts?: {
  agentId?: string;
  pageSize?: number;
}): UseInfiniteQueryResult<InfiniteListCache, ApiError> {
  const pageSize = opts?.pageSize ?? DEFAULT_PAGE_SIZE;
  const agentId = opts?.agentId;
  return useCursorInfiniteQuery<Conversation>({
    queryKey: qk.conversations.list(agentId),
    fetchPage: async (cursor) => {
      const data = await unwrap(
        await api.GET('/conversations', {
          params: {
            query: {
              cursor,
              pageSize,
              ...(agentId ? { agentId } : {}),
            },
          },
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
 * `GET /conversations/{conversationId}` — disabled when `conversationId` is
 * falsy so route mounts that haven't resolved the param yet stay quiet.
 */
export function useConversation(
  conversationId: string | undefined,
): UseQueryResult<Conversation, ApiError> {
  return useQuery<Conversation, ApiError>({
    queryKey: qk.conversations.byId(conversationId ?? ''),
    queryFn: async () => {
      const data = await unwrap(
        await api.GET('/conversations/{conversationId}', {
          params: { path: { conversationId: conversationId! } },
        }),
      );
      return data!;
    },
    enabled: Boolean(conversationId),
  });
}

/**
 * `GET /conversations/{conversationId}/messages?pageSize=64` — a single page
 * fits the 64-message cap (`REQ-CHAT-010`), so we avoid `useInfiniteQuery`
 * here. `staleTime: 0` + `refetchOnWindowFocus: false` per SW-DESIGN §7.6:
 * the messages cache is mutated explicitly by `useChatStream` (EPIC-07).
 */
export function useMessages(
  conversationId: string | undefined,
): UseQueryResult<Message[], ApiError> {
  return useQuery<Message[], ApiError>({
    queryKey: qk.conversations.messages(conversationId ?? ''),
    queryFn: async () => {
      const data = await unwrap(
        await api.GET('/conversations/{conversationId}/messages', {
          params: {
            path: { conversationId: conversationId! },
            query: { pageSize: MESSAGES_PAGE_SIZE },
          },
        }),
      );
      return data?.items ?? [];
    },
    enabled: Boolean(conversationId),
    refetchOnWindowFocus: false,
  });
}

/**
 * `POST /conversations` — creates an empty conversation for the given agent.
 * Invalidates `qk.conversations.all()` so every list variant (the unfiltered
 * list and any `agentId`-filtered list) re-fetches with the new entry.
 */
export function useStartConversation(): UseMutationResult<
  Conversation,
  ApiError,
  { agentId: string }
> {
  const queryClient = useQueryClient();
  return useMutation<Conversation, ApiError, { agentId: string }>({
    mutationFn: async ({ agentId }) => {
      const data = await unwrap(
        await api.POST('/conversations', { body: { agentId } }),
      );
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.conversations.all() });
    },
  });
}

type TitleSnapshot = {
  byId: Conversation | undefined;
  lists: Array<{ key: QueryKey; data: InfiniteListCache | undefined }>;
};

function patchListWithTitle(
  cache: InfiniteListCache | undefined,
  conversationId: string,
  title: string,
): InfiniteListCache | undefined {
  if (!cache) return cache;
  return {
    pageParams: cache.pageParams,
    pages: cache.pages.map((p) => ({
      ...p,
      items: p.items.map((c) =>
        c.id === conversationId ? { ...c, title } : c,
      ),
    })),
  };
}

function removeFromList(
  cache: InfiniteListCache | undefined,
  conversationId: string,
): InfiniteListCache | undefined {
  if (!cache) return cache;
  return {
    pageParams: cache.pageParams,
    pages: cache.pages.map((p) => ({
      ...p,
      items: p.items.filter((c) => c.id !== conversationId),
    })),
  };
}

/**
 * `PATCH /conversations/{conversationId}` — title-only update with optimistic
 * cache patching across the detail cache and every list-cache variant. On
 * error the snapshot is restored so the topbar and the left-pane list both
 * revert.
 */
export function useUpdateConversationTitle(
  conversationId: string,
): UseMutationResult<
  Conversation,
  ApiError,
  UpdateConversationRequest,
  TitleSnapshot
> {
  const queryClient = useQueryClient();
  return useMutation<Conversation, ApiError, UpdateConversationRequest, TitleSnapshot>({
    mutationFn: async (body) => {
      const data = await unwrap(
        await api.PATCH('/conversations/{conversationId}', {
          params: { path: { conversationId } },
          body,
        }),
      );
      return data!;
    },
    onMutate: async ({ title }) => {
      await queryClient.cancelQueries({ queryKey: qk.conversations.byId(conversationId) });
      await queryClient.cancelQueries({ queryKey: qk.conversations.all() });

      const byId = queryClient.getQueryData<Conversation>(
        qk.conversations.byId(conversationId),
      );
      if (byId) {
        queryClient.setQueryData<Conversation>(qk.conversations.byId(conversationId), {
          ...byId,
          title,
        });
      }

      const listEntries = queryClient.getQueriesData<InfiniteListCache>({
        queryKey: qk.conversations.all(),
      });
      const lists: TitleSnapshot['lists'] = [];
      for (const [key, data] of listEntries) {
        if (!Array.isArray(key) || key[1] !== 'list') continue;
        lists.push({ key, data });
        queryClient.setQueryData<InfiniteListCache>(
          key,
          patchListWithTitle(data, conversationId, title),
        );
      }

      return { byId, lists };
    },
    onError: (_err, _vars, context) => {
      if (!context) return;
      if (context.byId) {
        queryClient.setQueryData<Conversation>(
          qk.conversations.byId(conversationId),
          context.byId,
        );
      }
      for (const { key, data } of context.lists) {
        queryClient.setQueryData<InfiniteListCache>(key, data);
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: qk.conversations.byId(conversationId) });
      void queryClient.invalidateQueries({ queryKey: qk.conversations.all() });
    },
  });
}

type DeleteSnapshot = {
  lists: Array<{ key: QueryKey; data: InfiniteListCache | undefined }>;
};

/**
 * `DELETE /conversations/{conversationId}` — optimistic remove from every
 * list-cache variant. The detail cache is NOT invalidated on settle: by then
 * the server may have removed the row, and any further read of
 * `qk.conversations.byId(id)` should rely on the list refetch.
 */
export function useDeleteConversation(): UseMutationResult<
  void,
  ApiError,
  { conversationId: string; agentId?: string },
  DeleteSnapshot
> {
  const queryClient = useQueryClient();
  return useMutation<
    void,
    ApiError,
    { conversationId: string; agentId?: string },
    DeleteSnapshot
  >({
    mutationFn: async ({ conversationId }) => {
      await api.DELETE('/conversations/{conversationId}', {
        params: { path: { conversationId } },
      });
    },
    onMutate: async ({ conversationId }) => {
      await queryClient.cancelQueries({ queryKey: qk.conversations.all() });

      const listEntries = queryClient.getQueriesData<InfiniteListCache>({
        queryKey: qk.conversations.all(),
      });
      const lists: DeleteSnapshot['lists'] = [];
      for (const [key, data] of listEntries) {
        if (!Array.isArray(key) || key[1] !== 'list') continue;
        lists.push({ key, data });
        queryClient.setQueryData<InfiniteListCache>(key, removeFromList(data, conversationId));
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
      void queryClient.invalidateQueries({ queryKey: qk.conversations.all() });
    },
  });
}
