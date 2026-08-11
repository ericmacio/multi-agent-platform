import {
  useInfiniteQuery,
  type QueryKey,
  type UseInfiniteQueryResult,
} from '@tanstack/react-query';
import type { ApiError } from '@/shared/api/errors';

/**
 * Generic shape returned by every list endpoint in the openapi spec
 * (`PageEnvelope`). The generated openapi types resolve `items` to
 * `unknown[]` because each list endpoint overrides the item type via
 * `allOf` — this helper restates the envelope generically so feature
 * slices can type their items at the call site.
 */
export type PageEnvelope<T> = {
  items: T[];
  nextCursor?: string | null;
  pageSize: number;
};

/**
 * Flatten the pages of a TanStack `useInfiniteQuery` result into a single
 * `items[]` array. Defensive against `undefined` (first render before any
 * page has loaded).
 */
export function flattenPages<T>(infiniteData: { pages: PageEnvelope<T>[] } | undefined): T[] {
  return infiniteData?.pages.flatMap((p) => p.items) ?? [];
}

export type UseCursorInfiniteQueryOptions<T> = {
  queryKey: QueryKey;
  fetchPage: (cursor?: string) => Promise<PageEnvelope<T>>;
  enabled?: boolean;
  staleTime?: number;
};

/**
 * Thin wrapper over `useInfiniteQuery` for `PageEnvelope`-shaped endpoints.
 * The caller threads `pageSize` through `fetchPage` because the typed
 * `openapi-fetch` call site is where the path-typing happens; this helper
 * stays endpoint-agnostic.
 */
export function useCursorInfiniteQuery<T>(
  opts: UseCursorInfiniteQueryOptions<T>,
): UseInfiniteQueryResult<
  { pages: PageEnvelope<T>[]; pageParams: (string | undefined)[] },
  ApiError
> {
  return useInfiniteQuery<
    PageEnvelope<T>,
    ApiError,
    { pages: PageEnvelope<T>[]; pageParams: (string | undefined)[] },
    QueryKey,
    string | undefined
  >({
    queryKey: opts.queryKey,
    initialPageParam: undefined,
    queryFn: ({ pageParam }) => opts.fetchPage(pageParam),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: opts.enabled,
    staleTime: opts.staleTime,
  });
}
