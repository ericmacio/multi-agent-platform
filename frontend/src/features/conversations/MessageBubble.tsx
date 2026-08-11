import { cn } from '@/shared/lib/cn';
import { formatRelative } from '@/shared/lib/date';
import type { Message } from './schema';

export type MessageBubbleVariant = 'streaming' | 'stopped';

export type MessageBubbleProps = {
  message: Message;
  variant?: MessageBubbleVariant;
};

/**
 * USER vs ASSISTANT message rendering. Plain text only per SW-DESIGN §15 (no
 * Markdown in v1). The `variant` prop reserves the streaming caret and the
 * greyed "(stopped)" footer for EPIC-07 — in EPIC-06 the variant is never set
 * by `MessageList`, only by the SSE streaming hook in EPIC-07.
 */
export function MessageBubble({ message, variant }: MessageBubbleProps): JSX.Element {
  const isUser = message.role === 'USER';
  const isStopped = variant === 'stopped';
  const isStreaming = variant === 'streaming';

  return (
    <div
      data-message-id={message.id}
      className={cn('flex w-full', isUser ? 'justify-end' : 'justify-start')}
    >
      <div className="flex max-w-[70%] flex-col gap-1">
        <div
          className={cn(
            'rounded-2xl border px-3.5 py-2 text-sm shadow-sm',
            isUser
              ? 'bg-accent-bg text-accent border-border-accent text-left rounded-br-md'
              : 'bg-bg-elevated text-text-primary border-border-default text-left rounded-bl-md',
            isStopped && 'opacity-60',
          )}
          style={{ whiteSpace: 'pre-wrap' }}
        >
          {message.content}
          {isStreaming && (
            <span
              aria-hidden
              data-testid="streaming-caret"
              className="ml-0.5 inline-block h-3 w-[1px] animate-pulse bg-current align-baseline motion-reduce:animate-none"
            />
          )}
          {isStopped && (
            <span className="ml-2 text-xs text-text-disabled">(stopped)</span>
          )}
        </div>
        <p
          className={cn(
            'text-xs text-text-muted',
            isUser ? 'text-right' : 'text-left',
          )}
        >
          {formatRelative(message.createdAt)}
        </p>
      </div>
    </div>
  );
}
