import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import type { RateLimitConfig, RateLimitConfigRequest } from './schema';

/**
 * `GET /admin/rate-limit` — admin-only, single-object read of the global
 * rate-limit configuration. Standard defaults (no custom `staleTime`); the
 * mutation invalidates on success so this refetches automatically.
 */
export function useRateLimitConfig(): UseQueryResult<RateLimitConfig, ApiError> {
  return useQuery<RateLimitConfig, ApiError>({
    queryKey: qk.admin.rateLimit(),
    queryFn: async () => {
      const data = await unwrap(await api.GET('/admin/rate-limit'));
      return data!;
    },
  });
}

/**
 * `PUT /admin/rate-limit` — non-optimistic save-then-refetch. Rate-limit
 * changes are rare and cross-cutting; the admin should see the value the
 * server actually accepted (including the new `updatedAt` / `updatedBy`)
 * rather than a value that would silently roll back on error.
 */
export function useUpdateRateLimitConfig(): UseMutationResult<
  RateLimitConfig,
  ApiError,
  RateLimitConfigRequest
> {
  const queryClient = useQueryClient();
  return useMutation<RateLimitConfig, ApiError, RateLimitConfigRequest>({
    mutationFn: async (body) => {
      const data = await unwrap(await api.PUT('/admin/rate-limit', { body }));
      return data!;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: qk.admin.rateLimit() });
    },
  });
}
