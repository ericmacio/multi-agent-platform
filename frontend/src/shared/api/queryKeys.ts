/**
 * Single typed source of cache keys. Every TanStack Query call uses a `qk.*`
 * builder; no slice ever assembles a key as an ad-hoc string array.
 *
 * Structure mirrors SW-DESIGN §7.3. Mutations invalidate the smallest possible
 * subtree (e.g., `queryClient.invalidateQueries({ queryKey: qk.agents.all() })`
 * after a delete; `qk.agents.byId(id)` after an edit).
 */
export const qk = {
  me: () => ['me'] as const,
  agents: {
    all: () => ['agents'] as const,
    list: (cursor?: string) => ['agents', 'list', cursor ?? null] as const,
    byId: (id: string) => ['agents', 'byId', id] as const,
  },
  conversations: {
    all: () => ['conversations'] as const,
    list: (agentId?: string) => ['conversations', 'list', agentId ?? null] as const,
    byId: (id: string) => ['conversations', 'byId', id] as const,
    messages: (id: string) => ['conversations', 'messages', id] as const,
  },
  catalog: {
    tools: () => ['catalog', 'tools'] as const,
    mcpServers: () => ['catalog', 'mcpServers'] as const,
  },
  admin: {
    users: {
      all: () => ['admin', 'users'] as const,
      list: () => ['admin', 'users', 'list'] as const,
      byId: (id: string) => ['admin', 'users', 'byId', id] as const,
    },
    apiKeys: {
      all: () => ['admin', 'apiKeys'] as const,
      list: () => ['admin', 'apiKeys', 'list'] as const,
    },
    rateLimit: () => ['admin', 'rateLimit'] as const,
  },
} as const;
