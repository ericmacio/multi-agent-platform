import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react';
import { cn } from '@/shared/lib/cn';
import { Button } from '@/shared/ui/Button';
import { Send, Square } from '@/shared/ui/icons';

export type ChatPhase = 'idle' | 'sending' | 'streaming' | 'completed' | 'error';

export type ComposerProps = {
  phase: ChatPhase;
  onSend: (content: string) => void;
  onStop: () => void;
  disabled?: boolean;
};

const CONTENT_MAX = 1024;
const WARN_AT = 900;
const ROWS_MIN = 3;
const ROWS_MAX = 8;
const ROW_PX = 24;

function isMacPlatform(): boolean {
  if (typeof navigator === 'undefined') return false;
  const platform = (navigator as { userAgentData?: { platform?: string } }).userAgentData?.platform
    ?? navigator.platform
    ?? '';
  return /Mac|iPhone|iPad|iPod/i.test(platform);
}

/**
 * Controlled chat composer. Parent owns the chat-stream phase + send / stop
 * callbacks (US-07-002). The composer enforces the openapi 1024-char cap
 * client-side: above the limit the textarea preserves the content but Send is
 * disabled and an inline alert renders so the user can edit down.
 *
 * Keyboard contract (SW-DESIGN §11.6): `Cmd/Ctrl+Enter` sends, `Enter` alone
 * inserts a newline, `Esc` calls `onStop()` while streaming.
 */
export function Composer({
  phase,
  onSend,
  onStop,
  disabled = false,
}: ComposerProps): JSX.Element {
  const [content, setContent] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  // Auto-grow up to 8 rows, then scroll.
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    const maxHeight = ROWS_MAX * ROW_PX + 16; // padding allowance
    el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`;
  }, [content]);

  const length = content.length;
  const tooLong = length > CONTENT_MAX;
  const isStreaming = phase === 'streaming';
  const isSending = phase === 'sending';
  const sendDisabled =
    disabled || isSending || content.trim().length === 0 || tooLong;

  function commit(): void {
    if (sendDisabled) return;
    onSend(content);
    setContent('');
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>): void {
    const metaPressed = isMacPlatform() ? e.metaKey : e.ctrlKey;
    if (e.key === 'Enter' && metaPressed) {
      e.preventDefault();
      commit();
      return;
    }
    if (e.key === 'Escape') {
      if (isStreaming) {
        e.preventDefault();
        onStop();
      }
      return;
    }
    // Plain Enter is the default textarea behavior (newline).
  }

  const counterTone =
    length === 0
      ? 'text-text-muted'
      : tooLong
        ? 'text-danger'
        : length >= CONTENT_MAX
          ? 'text-danger'
          : length >= WARN_AT
            ? 'text-warning'
            : 'text-text-muted';

  return (
    <div
      className="shrink-0 border-t border-border-default bg-bg-surface px-4 py-3"
      data-testid="composer"
    >
      <div className="flex flex-col gap-2">
        <textarea
          ref={textareaRef}
          aria-label="Message composer"
          aria-invalid={tooLong || undefined}
          placeholder="Type your message…"
          rows={ROWS_MIN}
          value={content}
          disabled={disabled || isSending}
          onChange={(e) => setContent(e.target.value)}
          onKeyDown={onKeyDown}
          className={cn(
            'w-full resize-none rounded-md border bg-bg-elevated px-3 py-2 text-sm text-text-primary',
            'placeholder:text-text-muted',
            'outline-offset-2 focus-visible:outline-2 focus-visible:outline-border-focus',
            'disabled:cursor-not-allowed disabled:opacity-60',
            tooLong ? 'border-danger' : 'border-border-default',
          )}
          data-testid="composer-textarea"
        />

        {tooLong && (
          <p role="alert" className="text-xs text-danger" data-testid="composer-too-long">
            Message too long (max 1024 characters)
          </p>
        )}

        <div className="flex items-center justify-between gap-3">
          <p
            aria-live="polite"
            className={cn('text-xs tabular-nums transition-colors', counterTone)}
            data-testid="composer-counter"
          >
            {length} / {CONTENT_MAX}
          </p>
          {isStreaming ? (
            <Button
              variant="danger"
              size="sm"
              onClick={onStop}
              leftIcon={<Square aria-hidden width={14} height={14} />}
              data-testid="composer-stop"
            >
              Stop
            </Button>
          ) : (
            <Button
              variant="primary"
              size="sm"
              onClick={commit}
              loading={isSending}
              disabled={sendDisabled}
              leftIcon={<Send aria-hidden width={14} height={14} />}
              data-testid="composer-send"
            >
              Send
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
