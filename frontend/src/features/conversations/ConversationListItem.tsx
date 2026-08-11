import { forwardRef } from 'react';
import { useAgent } from '@/features/agents/api';
import { formatRelative } from '@/shared/lib/date';
import { cn } from '@/shared/lib/cn';
import { Badge } from '@/shared/ui/Badge';
import type { Conversation } from './schema';

export type ConversationListItemProps = {
  conversation: Conversation;
  active: boolean;
  onClick: () => void;
};

const MAX_MESSAGES = 64;

/**
 * Single row in the left-pane conversation list. The row is announced as
 * `role="option" aria-selected={active}` so the parent `ConversationList`
 * (`role="listbox"`) reads as a single semantic listbox per SW-DESIGN §11.6.
 *
 * The agent name is resolved opportunistically via `useAgent`: cache hits
 * (the common case after `AgentList`) render instantly; pending / error
 * states fall back to muted captions rather than blocking the row.
 */
export const ConversationListItem = forwardRef<HTMLDivElement, ConversationListItemProps>(
  function ConversationListItem({ conversation, active, onClick }, ref) {
    const agent = useAgent(conversation.agentId);
    const fallbackTitle = conversation.title === null || conversation.title === undefined;
    const titlePrefix = fallbackTitle ? 'chat-' : null;
    const titleBody = fallbackTitle
      ? conversation.id.slice(0, 8)
      : (conversation.title as string);

    const agentLabel =
      agent.isPending && agent.fetchStatus !== 'idle'
        ? 'Loading agent…'
        : agent.isError
          ? 'Unknown agent'
          : (agent.data?.name ?? 'Unknown agent');

    const atCap = conversation.messageCount >= MAX_MESSAGES;

    return (
      <div
        ref={ref}
        role="option"
        aria-selected={active}
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onClick();
          }
        }}
        data-testid={`conversation-item-${conversation.id}`}
        className={cn(
          'flex cursor-pointer flex-col gap-1 rounded-lg border bg-bg-surface px-3 py-2.5 outline-none shadow-sm',
          'transition-all duration-[80ms]',
          'focus-visible:outline-2 focus-visible:outline-border-focus',
          active
            ? 'border-l-4 border-accent bg-accent-bg shadow-md'
            : 'border-border-default hover:border-border-accent hover:shadow-md hover:-translate-y-px',
        )}
      >
        <div className="flex items-start justify-between gap-2">
          <p className="line-clamp-1 text-sm font-medium text-text-primary">
            {titlePrefix && <span className="font-mono text-text-muted">{titlePrefix}</span>}
            <span className={cn(fallbackTitle && 'font-mono text-text-secondary')}>
              {titleBody}
            </span>
          </p>
          <Badge variant={atCap ? 'warning' : 'neutral'} className="shrink-0">
            {`${conversation.messageCount} / ${MAX_MESSAGES}`}
          </Badge>
        </div>
        <p className="line-clamp-1 text-xs text-text-secondary">{agentLabel}</p>
        <p className="text-xs text-text-muted">{formatRelative(conversation.updatedAt)}</p>
      </div>
    );
  },
);
