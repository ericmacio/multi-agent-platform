import { startTransition, useCallback, useEffect, useReducer, useRef } from 'react';
import { useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/errors';
import { qk } from '@/shared/api/queryKeys';
import { streamChat } from '@/shared/sse/chatStream';
import type { SseFrame } from '@/shared/sse/sseFrames';
import type { PageEnvelope } from '@/shared/lib/pagination';
import type { Conversation, Message } from './schema';

export type ChatPhase = 'idle' | 'sending' | 'streaming' | 'completed' | 'error';

export type UseChatStreamResult = {
  phase: ChatPhase;
  pendingUserMessage: Message | null;
  pendingAssistantText: string;
  error: ApiError | null;
  send: (content: string) => Promise<void>;
  stop: () => void;
};

type State = {
  phase: ChatPhase;
  pendingUserMessage: Message | null;
  pendingAssistantText: string;
  error: ApiError | null;
};

const INITIAL_STATE: State = {
  phase: 'idle',
  pendingUserMessage: null,
  pendingAssistantText: '',
  error: null,
};

export type ChatStreamAction =
  | { type: 'send'; userMessage: Message }
  | { type: 'frame:started'; userMessageId: string }
  | { type: 'frame:delta'; text: string }
  | { type: 'frame:completed' }
  | { type: 'frame:error'; error: ApiError }
  | { type: 'http:error'; error: ApiError }
  | { type: 'cancel'; error: ApiError }
  | { type: 'reset' };

/**
 * Pure reducer for the chat-stream state machine. Exported for unit tests so
 * each transition can be exercised without spinning up the full hook.
 */
export function chatStreamReducer(state: State, action: ChatStreamAction): State {
  switch (action.type) {
    case 'send':
      return {
        phase: 'sending',
        pendingUserMessage: action.userMessage,
        pendingAssistantText: '',
        error: null,
      };
    case 'frame:started':
      return {
        ...state,
        phase: 'streaming',
        pendingUserMessage: state.pendingUserMessage
          ? { ...state.pendingUserMessage, id: action.userMessageId }
          : state.pendingUserMessage,
      };
    case 'frame:delta':
      return {
        ...state,
        phase: 'streaming',
        pendingAssistantText: state.pendingAssistantText + action.text,
      };
    case 'frame:completed':
      return { ...state, phase: 'completed' };
    case 'frame:error':
      // Preserve `pendingAssistantText` so the partial bubble can be rendered greyed.
      return { ...state, phase: 'error', error: action.error };
    case 'http:error':
      // Pre-stream HTTP error — there was no streamed content to preserve.
      return {
        ...state,
        phase: 'error',
        error: action.error,
        pendingAssistantText: '',
      };
    case 'cancel':
      return { ...state, phase: 'error', error: action.error };
    case 'reset':
      return INITIAL_STATE;
    default:
      return state;
  }
}

function makeTempId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `tmp-${Math.random().toString(36).slice(2)}-${Date.now()}`;
}

function cancelledError(): ApiError {
  return new ApiError({
    status: 0,
    code: 'CANCELLED',
    title: 'Cancelled',
    detail: 'You stopped the response.',
  });
}

function appendMessage(client: QueryClient, conversationId: string, message: Message): void {
  client.setQueryData<Message[]>(qk.conversations.messages(conversationId), (prev) => {
    if (!prev) return [message];
    return [...prev, message];
  });
}

function replaceMessageId(
  client: QueryClient,
  conversationId: string,
  oldId: string,
  newId: string,
): void {
  client.setQueryData<Message[]>(qk.conversations.messages(conversationId), (prev) => {
    if (!prev) return prev;
    return prev.map((m) => (m.id === oldId ? { ...m, id: newId } : m));
  });
}

function removeMessage(client: QueryClient, conversationId: string, id: string): void {
  client.setQueryData<Message[]>(qk.conversations.messages(conversationId), (prev) => {
    if (!prev) return prev;
    return prev.filter((m) => m.id !== id);
  });
}

type InfiniteListCache = {
  pages: PageEnvelope<Conversation>[];
  pageParams: (string | undefined)[];
};

function patchConversationLists(
  client: QueryClient,
  conversationId: string,
  patch: Partial<Conversation>,
): void {
  const entries = client.getQueriesData<InfiniteListCache>({
    queryKey: qk.conversations.all(),
  });
  for (const [key, data] of entries) {
    if (!Array.isArray(key) || (key as readonly unknown[])[1] !== 'list') continue;
    if (!data) continue;
    const next: InfiniteListCache = {
      pageParams: data.pageParams,
      pages: data.pages.map((p) => ({
        ...p,
        items: p.items.map((c) => (c.id === conversationId ? { ...c, ...patch } : c)),
      })),
    };
    client.setQueryData<InfiniteListCache>(key as QueryKey, next);
  }
}

/**
 * React-facing wrapper around `streamChat` (US-02-012). Owns the SSE state
 * machine (idle → sending → streaming → completed | error), the optimistic
 * USER bubble, in-place ASSISTANT bubble growth via `React.startTransition`,
 * cache patching on completion, and AbortController lifecycle (cancel via
 * `stop()` and on `conversationId` change / unmount).
 *
 * The hook does NOT own UI concerns (toasts, modals, banners) — those are
 * the consuming view's responsibility (US-07-003).
 */
export function useChatStream(conversationId: string): UseChatStreamResult {
  const client = useQueryClient();
  const [state, dispatch] = useReducer(chatStreamReducer, INITIAL_STATE);
  const controllerRef = useRef<AbortController | null>(null);
  const tempUserIdRef = useRef<string | null>(null);

  // Abort on conversationId change OR unmount. Per US-07-004, this releases
  // the wire when the user navigates away mid-stream.
  useEffect(() => {
    return () => {
      controllerRef.current?.abort();
      controllerRef.current = null;
      tempUserIdRef.current = null;
      dispatch({ type: 'reset' });
    };
  }, [conversationId]);

  const send = useCallback(
    async (content: string): Promise<void> => {
      // If a previous turn is still in flight, abort it first.
      controllerRef.current?.abort();

      const controller = new AbortController();
      controllerRef.current = controller;

      const tempId = makeTempId();
      tempUserIdRef.current = tempId;
      const userMessage: Message = {
        id: tempId,
        role: 'USER',
        content,
        createdAt: new Date().toISOString(),
      };
      dispatch({ type: 'send', userMessage });
      appendMessage(client, conversationId, userMessage);

      let firstFrameSeen = false;
      let assistantId: string | null = null;
      let finalTitle: string | null = null;
      let finalMessageCount: number | null = null;
      let assistantContent = '';

      // Snapshot the controller so the catch / finally below can tell
      // user-initiated stops (controller was the active one) from cleanup-
      // initiated aborts (the controllerRef has been cleared / reassigned).
      const myController = controller;
      try {
        await streamChat(conversationId, content, {
          signal: controller.signal,
          onFrame: (frame: SseFrame) => {
            if (frame.type === 'started') {
              firstFrameSeen = true;
              const oldId = tempUserIdRef.current;
              if (oldId) {
                replaceMessageId(client, conversationId, oldId, frame.userMessageId);
              }
              tempUserIdRef.current = frame.userMessageId;
              dispatch({ type: 'frame:started', userMessageId: frame.userMessageId });
              return;
            }
            if (frame.type === 'delta') {
              firstFrameSeen = true;
              assistantContent += frame.text;
              startTransition(() => {
                dispatch({ type: 'frame:delta', text: frame.text });
              });
              return;
            }
            if (frame.type === 'completed') {
              assistantId = frame.assistantMessageId;
              finalTitle = frame.title;
              finalMessageCount = frame.messageCount;
              return;
            }
            // The 'error' frame is handled below in the catch (streamChat
            // rejects with the matching ApiError).
          },
        });
        // Reached only on a clean `completed` frame.
        if (assistantId) {
          const assistantMessage: Message = {
            id: assistantId,
            role: 'ASSISTANT',
            content: assistantContent,
            createdAt: new Date().toISOString(),
          };
          appendMessage(client, conversationId, assistantMessage);
          const patch: Partial<Conversation> = {};
          if (finalMessageCount !== null) patch.messageCount = finalMessageCount;
          if (finalTitle !== null) patch.title = finalTitle;
          if (Object.keys(patch).length > 0) {
            patchConversationLists(client, conversationId, patch);
          }
          void client.invalidateQueries({ queryKey: qk.conversations.byId(conversationId) });
          dispatch({ type: 'frame:completed' });
        }
      } catch (e) {
        // Cleanup (conversationId change / unmount) cleared the ref. The
        // hook has already dispatched `reset`; surfacing a cancel error here
        // would race with that reset, so we stay quiet.
        if (controllerRef.current !== myController) {
          return;
        }
        if (controller.signal.aborted) {
          // User-initiated stop. Keep partial text.
          dispatch({ type: 'cancel', error: cancelledError() });
          return;
        }
        const apiErr =
          e instanceof ApiError
            ? e
            : new ApiError({
                status: 0,
                code: 'INTERNAL_ERROR',
                title: 'Internal error',
                detail: (e as Error)?.message ?? 'Unknown error',
              });
        if (!firstFrameSeen) {
          // Pre-stream HTTP error — roll back the optimistic USER bubble.
          if (tempUserIdRef.current) {
            removeMessage(client, conversationId, tempUserIdRef.current);
            tempUserIdRef.current = null;
          }
          dispatch({ type: 'http:error', error: apiErr });
        } else {
          dispatch({ type: 'frame:error', error: apiErr });
        }
      }
    },
    [client, conversationId],
  );

  const stop = useCallback(() => {
    controllerRef.current?.abort();
  }, []);

  return {
    phase: state.phase,
    pendingUserMessage: state.pendingUserMessage,
    pendingAssistantText: state.pendingAssistantText,
    error: state.error,
    send,
    stop,
  };
}
