import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useVirtualizer } from '@tanstack/react-virtual';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Skeleton } from '@/shared/ui/Skeleton';
import { useMessages } from './api';
import { MessageBubble } from './MessageBubble';

export type PendingAssistant = {
  text: string;
  variant: 'streaming' | 'stopped';
};

export type MessageListProps = {
  conversationId: string;
  /**
   * Synthetic ASSISTANT bubble appended after the persisted messages. Used by
   * `useChatStream` (US-07-002 / US-07-003) to render the in-flight or
   * stopped partial response. The bubble is `aria-hidden` so the dedicated
   * live region in `ConversationView` owns the screen-reader announcement.
   */
  pendingAssistant?: PendingAssistant | null;
};

const ROW_ESTIMATE = 80;

/**
 * Renders every persisted message of a conversation, virtualized via
 * `@tanstack/react-virtual` (SW-DESIGN §12.8). The 64-row cap means
 * virtualization is overkill in the worst case — but a single long bubble
 * easily breaks scrollback otherwise.
 *
 * The scroll container is announced as `role="log" aria-live="polite"` so
 * EPIC-07's `completed` ASSISTANT message can be picked up by screen readers
 * (per SW-DESIGN §11.6, per-delta text is intentionally NOT announced).
 */
export function MessageList({
  conversationId,
  pendingAssistant = null,
}: MessageListProps): JSX.Element {
  const query = useMessages(conversationId);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const items = query.data ?? [];
  const totalRows = items.length + (pendingAssistant ? 1 : 0);

  const virtualizer = useVirtualizer({
    count: totalRows,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_ESTIMATE,
    overscan: 6,
  });

  // Auto-scroll to bottom on mount and whenever rows grow (including the
  // pending row growing across deltas).
  useEffect(() => {
    if (totalRows === 0) return;
    virtualizer.scrollToIndex(totalRows - 1, { align: 'end' });
  }, [totalRows, pendingAssistant?.text, virtualizer]);

  if (query.isPending) {
    return (
      <div className="flex flex-col gap-3 p-4" data-testid="message-list-loading">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className={i % 2 === 0 ? 'flex justify-end' : 'flex justify-start'}>
            <Skeleton height={60} width="60%" />
          </div>
        ))}
      </div>
    );
  }

  if (query.isError) {
    const status = query.error.status;
    if (status === 404) {
      return (
        <EmptyState
          title="Conversation not found"
          description="This conversation no longer exists or you don't have access to it."
          action={
            <Link to="/chat">
              <Button variant="secondary" size="sm">
                Back to chats
              </Button>
            </Link>
          }
        />
      );
    }
    return (
      <Card padding="md" className="m-4 border-danger/40">
        <div className="flex flex-col items-start gap-3" role="alert">
          <div>
            <p className="text-sm font-medium text-text-primary">
              {errorCopy[query.error.code]?.title ?? errorCopy.__unknown__.title}
            </p>
            <p className="text-sm text-text-secondary">
              {query.error.detail ??
                errorCopy[query.error.code]?.detail ??
                errorCopy.__unknown__.detail}
            </p>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
            Retry
          </Button>
        </div>
      </Card>
    );
  }

  if (items.length === 0 && !pendingAssistant) {
    return (
      <EmptyState
        title="No messages yet"
        description="Send a message below to start the conversation."
      />
    );
  }

  const virtualItems = virtualizer.getVirtualItems();

  // Virtualizer fallback: in jsdom and during the very first paint before the
  // ResizeObserver fires, `getVirtualItems()` returns []. Render the plain
  // list in that case so messages are always visible. Real browsers replace
  // this with the windowed render on the next frame.
  if (virtualItems.length === 0) {
    return (
      <div
        ref={scrollRef}
        role="log"
        aria-live="polite"
        aria-relevant="additions"
        data-testid="message-list"
        className="flex h-full flex-col gap-3 overflow-y-auto px-4 py-3"
      >
        {items.map((message) => (
          <MessageBubble key={message.id} message={message} />
        ))}
        {pendingAssistant && (
          <div aria-hidden="true" data-testid="pending-assistant-row">
            <MessageBubble
              message={{
                id:
                  pendingAssistant.variant === 'stopped'
                    ? 'pending-assistant-stopped'
                    : 'pending-assistant',
                role: 'ASSISTANT',
                content: pendingAssistant.text,
                createdAt: new Date().toISOString(),
              }}
              variant={pendingAssistant.variant}
            />
          </div>
        )}
      </div>
    );
  }

  return (
    <div
      ref={scrollRef}
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      data-testid="message-list"
      className="h-full overflow-y-auto px-4 py-3"
    >
      <div
        style={{ height: virtualizer.getTotalSize(), width: '100%', position: 'relative' }}
      >
        {virtualItems.map((vrow) => {
          const isPendingRow = pendingAssistant && vrow.index === items.length;
          if (isPendingRow) {
            const pendingId =
              pendingAssistant.variant === 'stopped'
                ? 'pending-assistant-stopped'
                : 'pending-assistant';
            return (
              <div
                key={pendingId}
                data-index={vrow.index}
                ref={virtualizer.measureElement}
                aria-hidden="true"
                data-testid="pending-assistant-row"
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  transform: `translateY(${vrow.start}px)`,
                }}
                className="pb-3"
              >
                <MessageBubble
                  message={{
                    id: pendingId,
                    role: 'ASSISTANT',
                    content: pendingAssistant.text,
                    createdAt: new Date().toISOString(),
                  }}
                  variant={pendingAssistant.variant}
                />
              </div>
            );
          }
          const message = items[vrow.index];
          if (!message) return null;
          return (
            <div
              key={message.id}
              data-index={vrow.index}
              ref={virtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${vrow.start}px)`,
              }}
              className="pb-3"
            >
              <MessageBubble message={message} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
