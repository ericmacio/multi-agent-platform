import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAgent } from '@/features/agents/api';
import { errorCopy } from '@/shared/i18n/en';
import { qk } from '@/shared/api/queryKeys';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/shared/ui/Dropdown';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Skeleton } from '@/shared/ui/Skeleton';
import { toast } from '@/shared/ui/Toast';
import { showRateLimitedToast } from '@/shared/ui/toastPolicy';
import { MoreHorizontal, Pencil, Trash2 } from '@/shared/ui/icons';
import { useConversation } from './api';
import { Composer } from './Composer';
import { DeleteConversationDialog } from './DeleteConversationDialog';
import { EditTitleDialog } from './EditTitleDialog';
import { MessageList, type PendingAssistant } from './MessageList';
import { useChatStream } from './useChatStream';
import type { Conversation } from './schema';

export type ConversationViewProps = {
  conversationId: string;
  onDeleted: (conversation: Conversation) => void;
  onStartNew: (agentId: string) => void;
};

const MAX_MESSAGES = 64;

/**
 * Right-pane shell of `ChatPage`. Hosts the topbar, the message list, the
 * SSE-driven composer, and the conversation-full banner. The SSE state
 * machine lives in `useChatStream`; this component is purely UI composition
 * + a small effect map (toast on error, banner on `CONVERSATION_FULL`, live
 * region on `completed`).
 */
export function ConversationView({
  conversationId,
  onDeleted,
  onStartNew,
}: ConversationViewProps): JSX.Element {
  const conversation = useConversation(conversationId);
  const agent = useAgent(conversation.data?.agentId);
  const chat = useChatStream(conversationId);
  const queryClient = useQueryClient();
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [liveRegionText, setLiveRegionText] = useState('');
  const lastErrorRef = useRef<string | null>(null);
  const lastFullConvRef = useRef<string | null>(null);

  // Toast routing for chat errors (SW-DESIGN §10.2). The per-code policy lives
  // in `errorCopy[code].toast`; this effect honors it. NOT_ACCEPTABLE is also
  // logged via console.error — that 406 should never reach prod and an
  // engineering eye on it matters more than a generic toast (US-07-005).
  useEffect(() => {
    if (chat.phase !== 'error' || !chat.error) {
      lastErrorRef.current = null;
      return;
    }
    const key = `${chat.error.code}:${chat.error.detail ?? ''}`;
    if (lastErrorRef.current === key) return;
    lastErrorRef.current = key;

    if (chat.error.code === 'NOT_ACCEPTABLE') {
      // eslint-disable-next-line no-console
      console.error(
        'Chat send received HTTP 406 NOT_ACCEPTABLE — the SSE Accept header ' +
          'should have prevented this. Investigate the backend / proxy chain.',
      );
    }

    const copy = errorCopy[chat.error.code] ?? errorCopy.__unknown__;
    if (copy.toast === 'off') return;

    if (chat.error.code === 'RATE_LIMITED') {
      showRateLimitedToast(chat.error.retryAfterSeconds);
      return;
    }
    const message = `${copy.title} — ${chat.error.detail ?? copy.detail}`;
    toast.error(message);
  }, [chat.phase, chat.error]);

  // Defense-in-depth: if the server returns CONVERSATION_FULL even though our
  // client-side cap should have caught it, refresh the conversation so the
  // banner takes over (REQ-CHAT-010).
  useEffect(() => {
    if (chat.error?.code !== 'CONVERSATION_FULL') {
      lastFullConvRef.current = null;
      return;
    }
    if (lastFullConvRef.current === conversationId) return;
    lastFullConvRef.current = conversationId;
    void queryClient.invalidateQueries({ queryKey: qk.conversations.byId(conversationId) });
  }, [chat.error, conversationId, queryClient]);

  // Live region: on `completed`, announce the full assistant text once. On
  // `sending` / `streaming`, keep it empty so per-delta noise doesn't leak.
  useEffect(() => {
    if (chat.phase === 'completed' && chat.pendingAssistantText) {
      setLiveRegionText(chat.pendingAssistantText);
      const handle = window.setTimeout(() => setLiveRegionText(''), 100);
      return () => window.clearTimeout(handle);
    }
    if (chat.phase === 'sending' || chat.phase === 'streaming') {
      setLiveRegionText('');
    }
    return undefined;
  }, [chat.phase, chat.pendingAssistantText]);

  const pendingAssistant: PendingAssistant | null = useMemo(() => {
    if (chat.phase === 'streaming' && chat.pendingAssistantText) {
      return { text: chat.pendingAssistantText, variant: 'streaming' };
    }
    if (chat.phase === 'error' && chat.pendingAssistantText) {
      return { text: chat.pendingAssistantText, variant: 'stopped' };
    }
    return null;
  }, [chat.phase, chat.pendingAssistantText]);

  if (conversation.isPending) {
    return (
      <div className="flex h-full flex-col">
        <Topbar>
          <Skeleton height={20} width={180} />
          <Skeleton height={20} width={120} />
        </Topbar>
        <div className="flex-1 overflow-hidden">
          <MessageList conversationId={conversationId} />
        </div>
      </div>
    );
  }

  if (conversation.isError) {
    if (conversation.error.status === 404) {
      return (
        <div className="flex h-full items-center justify-center">
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
        </div>
      );
    }
    return (
      <div className="flex h-full flex-col">
        <Topbar>
          <Card padding="sm" className="w-full border-danger/40">
            <div className="flex items-center justify-between gap-3" role="alert">
              <p className="text-sm text-text-primary">
                {errorCopy[conversation.error.code]?.title ?? errorCopy.__unknown__.title}
              </p>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => void conversation.refetch()}
              >
                Retry
              </Button>
            </div>
          </Card>
        </Topbar>
      </div>
    );
  }

  const conv = conversation.data;
  const fallbackTitle = conv.title === null || conv.title === undefined;
  const displayTitle = fallbackTitle ? `chat-${conv.id.slice(0, 8)}` : (conv.title as string);
  const serverAtCap = conv.messageCount >= MAX_MESSAGES;
  const cancelledStreamAtCap = chat.error?.code === 'CONVERSATION_FULL';
  const atCap = serverAtCap || cancelledStreamAtCap;

  const agentLabel =
    agent.isPending && agent.fetchStatus !== 'idle'
      ? 'Loading…'
      : agent.isError
        ? 'Unknown agent'
        : (agent.data?.name ?? 'Unknown agent');

  return (
    <div className="flex h-full flex-col" data-testid="conversation-view">
      <Topbar>
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <div className="flex min-w-0 items-center gap-1.5">
            <h1
              className={`truncate text-sm font-medium text-text-on-ink ${
                fallbackTitle ? 'font-mono text-text-on-ink-2' : ''
              }`}
              data-testid="conversation-title"
            >
              {displayTitle}
            </h1>
            <button
              type="button"
              aria-label="Rename conversation"
              onClick={() => setEditOpen(true)}
              className="shrink-0 rounded-sm p-1 text-text-on-ink-3 transition-colors hover:bg-bg-ink-2 hover:text-text-on-ink focus-visible:outline-2 focus-visible:outline-border-focus"
            >
              <Pencil aria-hidden width={14} height={14} />
            </button>
          </div>
          <Link
            to={`/agents/${conv.agentId}`}
            className="truncate text-xs text-text-on-ink-2 hover:text-accent-dim"
            data-testid="conversation-agent-link"
          >
            → {agentLabel}
          </Link>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Badge variant={atCap ? 'warning' : 'neutral'} aria-label="Message count">
            {`${conv.messageCount} / ${MAX_MESSAGES}`}
          </Badge>
          <Dropdown>
            <DropdownTrigger
              aria-label="Conversation actions"
              className="rounded-sm p-1 text-text-on-ink-3 hover:bg-bg-ink-2 hover:text-text-on-ink focus-visible:outline-2 focus-visible:outline-border-focus"
            >
              <MoreHorizontal aria-hidden width={16} height={16} />
            </DropdownTrigger>
            <DropdownContent align="end">
              <DropdownItem onClick={() => setDeleteOpen(true)} className="text-danger">
                <Trash2 aria-hidden width={14} height={14} />
                Delete conversation
              </DropdownItem>
            </DropdownContent>
          </Dropdown>
        </div>
      </Topbar>

      <div className="flex-1 overflow-hidden">
        <MessageList conversationId={conversationId} pendingAssistant={pendingAssistant} />
      </div>

      {atCap ? (
        <ConversationFullBanner onStartNew={() => onStartNew(conv.agentId)} />
      ) : (
        <Composer
          phase={chat.phase}
          onSend={(content) => {
            void chat.send(content);
          }}
          onStop={chat.stop}
        />
      )}

      {/* Visually hidden live region — announces the completed assistant text
          once, then clears. Per SW-DESIGN §11.6, per-delta text is NOT
          announced; the `aria-hidden` pending row above mutes the deltas. */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="chat-live-region"
        className="sr-only"
      >
        {liveRegionText}
      </div>

      <EditTitleDialog
        conversation={conv}
        open={editOpen}
        onClose={() => setEditOpen(false)}
      />
      <DeleteConversationDialog
        conversation={conv}
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onDeleted={(deleted) => {
          toast.success('Conversation deleted.');
          onDeleted(deleted);
        }}
      />
    </div>
  );
}

function Topbar({ children }: { children: React.ReactNode }): JSX.Element {
  return (
    <header
      className="flex shrink-0 items-center justify-between gap-3 border-b border-border-ink bg-bg-ink px-4 py-2 text-text-on-ink"
      data-testid="conversation-topbar"
    >
      {children}
    </header>
  );
}

function ConversationFullBanner({ onStartNew }: { onStartNew: () => void }): JSX.Element {
  return (
    <div
      className="shrink-0 border-t border-border-default bg-bg-surface px-4 py-3"
      data-testid="composer-full-banner"
    >
      <Card padding="sm" className="border-warning/40 bg-warning-bg/30">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-col">
            <p className="text-sm font-medium text-text-primary">Conversation full</p>
            <p className="text-xs text-text-secondary">
              This conversation has reached its 64-message cap.
            </p>
          </div>
          <Button variant="primary" size="sm" onClick={onStartNew}>
            Start a new conversation
          </Button>
        </div>
      </Card>
    </div>
  );
}
