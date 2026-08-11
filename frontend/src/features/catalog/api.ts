import { useQuery, type QueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import type { components } from '@/generated/schema';

export type ToolDescriptor = components['schemas']['ToolDescriptor'];
export type McpServerDescriptor = components['schemas']['McpServerDescriptor'];

/**
 * `GET /tools` — static tool catalog. Backed by `staleTime: Infinity` per
 * SW-DESIGN §7.6: the catalog is discovered by the backend at startup
 * (`REQ-TOOL-001`) and never changes at runtime, so a single fetch per
 * session is enough. Catalogs are evicted on sign-out via
 * `invalidateCatalogs(queryClient)`.
 */
export function useTools(): UseQueryResult<ToolDescriptor[], ApiError> {
  return useQuery<ToolDescriptor[], ApiError>({
    queryKey: qk.catalog.tools(),
    queryFn: async () => {
      const data = await unwrap(await api.GET('/tools'));
      return data?.items ?? [];
    },
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/**
 * `GET /mcp-servers` — static MCP-server catalog. Same caching policy as
 * `useTools` (`REQ-MCP-001`).
 */
export function useMcpServers(): UseQueryResult<McpServerDescriptor[], ApiError> {
  return useQuery<McpServerDescriptor[], ApiError>({
    queryKey: qk.catalog.mcpServers(),
    queryFn: async () => {
      const data = await unwrap(await api.GET('/mcp-servers'));
      return data?.items ?? [];
    },
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/**
 * Evicts both catalog caches. Called from the sign-out path so a new user
 * landing in the same tab doesn't observe the previous user's snapshot.
 */
export function invalidateCatalogs(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: qk.catalog.tools() });
  void queryClient.invalidateQueries({ queryKey: qk.catalog.mcpServers() });
}
