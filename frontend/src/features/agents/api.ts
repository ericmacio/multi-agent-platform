import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import { useCursorInfiniteQuery, type PageEnvelope } from '@/shared/lib/pagination';
import type { Agent, AgentRequest } from './schema';

const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /agents` — owner-scoped, cursor-paginated list of agents. Default
 * page size 20 per openapi. Caller flattens via `flattenPages()`.
 */
export function useAgents(opts?: {
  pageSize?: number;
}): UseInfiniteQueryResult<
  { pages: PageEnvelope<Agent>[]; pageParams: (string | undefined)[] },
  ApiError
> {
  const pageSize = opts?.pageSize ?? DEFAULT_PAGE_SIZE;
  return useCursorInfiniteQuery<Agent>({
    queryKey: qk.agents.list(),
    fetchPage: async (cursor) => {
      const data = await unwrap(
        await api.GET('/agents', {
          params: { query: { cursor, pageSize } },
        }),
      );
      // The generated `AgentPage.items` is typed as optional via the `allOf`
      // composition. Normalize to a non-optional array for downstream
      // consumers.
      return {
        items: data?.items ?? [],
        nextCursor: data?.nextCursor ?? null,
        pageSize: data?.pageSize ?? pageSize,
      };
    },
  });
}

/**
 * `GET /agents/{agentId}` — disabled when `agentId` is falsy so route
 * mounts that haven't resolved the param yet stay quiet.
 */
export function useAgent(agentId: string | undefined): UseQueryResult<Agent, ApiError> {
  return useQuery<Agent, ApiError>({
    queryKey: qk.agents.byId(agentId ?? ''),
    queryFn: async () => {
      const data = await unwrap(
        await api.GET('/agents/{agentId}', {
          params: { path: { agentId: agentId! } },
        }),
      );
      return data!;
    },
    enabled: Boolean(agentId),
  });
}

/**
 * `POST /agents` — invalidates the agents list on success so the next list
 * read re-fetches with the new entry. The detail cache is not pre-seeded;
 * a clean refetch keeps the cache honest.
 */
export function useCreateAgent(): UseMutationResult<Agent, ApiError, AgentRequest> {
  const queryClient = useQueryClient();
  return useMutation<Agent, ApiError, AgentRequest>({
    mutationFn: async (body) => {
      const data = await unwrap(await api.POST('/agents', { body }));
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.agents.all() });
    },
  });
}

/**
 * `PUT /agents/{agentId}` — full replace. Invalidates both the list (rank
 * may change) and the detail.
 */
export function useUpdateAgent(
  agentId: string,
): UseMutationResult<Agent, ApiError, AgentRequest> {
  const queryClient = useQueryClient();
  return useMutation<Agent, ApiError, AgentRequest>({
    mutationFn: async (body) => {
      const data = await unwrap(
        await api.PUT('/agents/{agentId}', {
          params: { path: { agentId } },
          body,
        }),
      );
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.agents.all() });
      void queryClient.invalidateQueries({ queryKey: qk.agents.byId(agentId) });
    },
  });
}

/**
 * `DELETE /agents/{agentId}` — cascades to all conversations referencing
 * this agent (`REQ-AGT-010`). Invalidates the agents list AND the
 * conversations cache so a stale "Recent conversations" panel for the
 * deleted agent disappears on next render.
 */
export function useDeleteAgent(): UseMutationResult<void, ApiError, { agentId: string }> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, { agentId: string }>({
    mutationFn: async ({ agentId }) => {
      await api.DELETE('/agents/{agentId}', {
        params: { path: { agentId } },
      });
      // 204 No Content; nothing to return.
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.agents.all() });
      void queryClient.invalidateQueries({ queryKey: qk.conversations.all() });
    },
  });
}
