import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { useAgents } from '@/features/agents/api';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Skeleton } from '@/shared/ui/Skeleton';
import { MessageSquare, Plus, Search } from '@/shared/ui/icons';
import { useConversations } from './api';
import { ConversationListItem } from './ConversationListItem';
import type { Conversation } from './schema';

export type ConversationListProps = {
  activeConversationId?: string;
  /** When set, scopes the underlying `useConversations` call to a single agent (US-06-007). */
  agentId?: string;
  onSelect: (id: string) => void;
  onNew: () => void;
};

/**
 * Left-pane conversation browser for `ChatPage`. Owns its own search input
 * (client-side filter — the API does not expose server-side title search)
 * and resolves agent names opportunistically via `useAgents` so filtering
 * by agent name works without N HTTP round-trips.
 *
 * The container is a `role="listbox"` with roving-tabindex items. The
 * optimistic-title patch from `useUpdateConversationTitle` (US-06-001)
 * does not bump `updatedAt`, so renames preserve the row position.
 */
export function ConversationList({
  activeConversationId,
  agentId,
  onSelect,
  onNew,
}: ConversationListProps): JSX.Element {
  const [query, setQuery] = useState('');
  const conversations = useConversations({ agentId });
  const agents = useAgents();

  useEffect(() => {
    if (agents.hasNextPage && !agents.isFetchingNextPage) {
      void agents.fetchNextPage();
    }
  }, [agents]);

  const items: Conversation[] = useMemo(
    () => flattenPages(conversations.data),
    [conversations.data],
  );
  const agentNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of flattenPages(agents.data)) map.set(a.id, a.name);
    return map;
  }, [agents.data]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q === '') return items;
    return items.filter((c) => {
      const haystack = `${c.title ?? ''} ${agentNameById.get(c.agentId) ?? ''}`.toLowerCase();
      return haystack.includes(q);
    });
  }, [items, query, agentNameById]);

  const itemRefs = useRef<Array<HTMLDivElement | null>>([]);
  const [focusedIndex, setFocusedIndex] = useState(0);

  useEffect(() => {
    if (focusedIndex >= filtered.length) {
      setFocusedIndex(Math.max(0, filtered.length - 1));
    }
  }, [filtered.length, focusedIndex]);

  function onListKeyDown(e: KeyboardEvent<HTMLDivElement>): void {
    if (filtered.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      const next = Math.min(filtered.length - 1, focusedIndex + 1);
      setFocusedIndex(next);
      itemRefs.current[next]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const next = Math.max(0, focusedIndex - 1);
      setFocusedIndex(next);
      itemRefs.current[next]?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      setFocusedIndex(0);
      itemRefs.current[0]?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      const last = filtered.length - 1;
      setFocusedIndex(last);
      itemRefs.current[last]?.focus();
    }
  }

  return (
    <div className="flex h-full flex-col gap-3 p-3" data-testid="conversation-list">
      <header className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-text-primary">Chats</h2>
        <Button
          variant="primary"
          size="sm"
          onClick={onNew}
          aria-label="Start a new chat"
          leftIcon={<Plus width={14} height={14} aria-hidden />}
        >
          New
        </Button>
      </header>

      <div className="flex items-center gap-2">
        <Search aria-hidden width={14} height={14} className="text-text-muted" />
        <div className="flex-1">
          <Input
            aria-label="Search conversations"
            placeholder="Search…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      </div>

      <ListBody
        isPending={conversations.isPending}
        isError={conversations.isError}
        errorCode={conversations.error?.code}
        errorDetail={conversations.error?.detail ?? null}
        onRetry={() => void conversations.refetch()}
        totalCount={items.length}
        filteredCount={filtered.length}
        query={query}
        clearQuery={() => setQuery('')}
        onNew={onNew}
      >
        <div
          role="listbox"
          aria-label="Conversations"
          tabIndex={-1}
          onKeyDown={onListKeyDown}
          className="flex flex-1 flex-col gap-2 overflow-y-auto"
          data-testid="conversation-listbox"
        >
          {filtered.map((c, i) => (
            <ConversationListItem
              key={c.id}
              ref={(el: HTMLDivElement | null) => {
                itemRefs.current[i] = el;
              }}
              conversation={c}
              active={c.id === activeConversationId}
              onClick={() => {
                setFocusedIndex(i);
                onSelect(c.id);
              }}
            />
          ))}
        </div>

        {conversations.hasNextPage && (
          <div className="flex justify-center">
            <Button
              variant="secondary"
              size="sm"
              loading={conversations.isFetchingNextPage}
              onClick={() => void conversations.fetchNextPage()}
            >
              Load more
            </Button>
          </div>
        )}
      </ListBody>
    </div>
  );
}

type ListBodyProps = {
  isPending: boolean;
  isError: boolean;
  errorCode: string | undefined;
  errorDetail: string | null;
  onRetry: () => void;
  totalCount: number;
  filteredCount: number;
  query: string;
  clearQuery: () => void;
  onNew: () => void;
  children: ReactNode;
};

function ListBody({
  isPending,
  isError,
  errorCode,
  errorDetail,
  onRetry,
  totalCount,
  filteredCount,
  query,
  clearQuery,
  onNew,
  children,
}: ListBodyProps): JSX.Element {
  if (isPending) {
    return (
      <div className="flex flex-col gap-2" data-testid="conversation-list-loading">
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} height={64} />
        ))}
      </div>
    );
  }

  if (isError) {
    const copy =
      (errorCode && errorCopy[errorCode as keyof typeof errorCopy]) ?? errorCopy.__unknown__;
    return (
      <Card padding="md" className="border-danger/40">
        <div className="flex flex-col items-start gap-3" role="alert">
          <div>
            <p className="text-sm font-medium text-text-primary">{copy.title}</p>
            <p className="text-sm text-text-secondary">{errorDetail ?? copy.detail}</p>
          </div>
          <Button variant="secondary" size="sm" onClick={onRetry}>
            Retry
          </Button>
        </div>
      </Card>
    );
  }

  if (totalCount === 0) {
    return (
      <EmptyState
        icon={<MessageSquare aria-hidden />}
        title="No chats yet"
        description="Start a conversation with one of your agents."
        action={
          <Button variant="primary" size="sm" onClick={onNew}>
            Start a chat
          </Button>
        }
      />
    );
  }

  if (filteredCount === 0) {
    return (
      <div
        className="flex flex-col items-start gap-2 px-1 py-3 text-sm text-text-secondary"
        data-testid="conversation-list-no-matches"
      >
        <p>{`No chats match "${query}"`}</p>
        <Button variant="ghost" size="sm" onClick={clearQuery}>
          Clear
        </Button>
      </div>
    );
  }

  return <>{children}</>;
}
