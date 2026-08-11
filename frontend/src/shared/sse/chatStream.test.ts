import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { ApiError } from '@/shared/api/errors';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { streamChat } from './chatStream';
import type { SseFrame } from './sseFrames';

const BASE = 'http://localhost:8080/api/v1';
const CONV_ID = 'c1';
const URL = `${BASE}/conversations/${CONV_ID}/messages`;

type FrameInput = { event: string; data: string };

function sseBody(frames: FrameInput[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(`event: ${frame.event}\ndata: ${frame.data}\n\n`));
      }
      controller.close();
    },
  });
}

function controlledSseBody(): {
  body: ReadableStream<Uint8Array>;
  push: (frame: FrameInput) => void;
  close: () => void;
} {
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    body,
    push: ({ event, data }) => {
      controller?.enqueue(encoder.encode(`event: ${event}\ndata: ${data}\n\n`));
    },
    close: () => {
      controller?.close();
    },
  };
}

function sseResponse(body: ReadableStream<Uint8Array>): Response {
  return new HttpResponse(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  }) as unknown as Response;
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
  vi.restoreAllMocks();
});

describe('streamChat', () => {
  test('golden path: started → delta × N → completed resolves and delivers all frames', async () => {
    server.use(
      http.post(URL, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"c1"}' },
            { event: 'delta', data: '{"text":"Hello"}' },
            { event: 'delta', data: '{"text":", world!"}' },
            {
              event: 'completed',
              data: '{"assistantMessageId":"a1","title":"Hello, world!","messageCount":2}',
            },
          ]),
        ),
      ),
    );
    const frames: SseFrame[] = [];
    const ac = new AbortController();
    await streamChat(CONV_ID, 'hi', {
      signal: ac.signal,
      onFrame: (f) => frames.push(f),
    });
    expect(frames.map((f) => f.type)).toEqual(['started', 'delta', 'delta', 'completed']);
    const last = frames[3];
    expect(last && last.type === 'completed' && last.title).toBe('Hello, world!');
  });

  test('error frame mid-stream: onFrame still fires, then the promise rejects with ApiError', async () => {
    server.use(
      http.post(URL, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"c1"}' },
            { event: 'delta', data: '{"text":"partial"}' },
            {
              event: 'error',
              data: '{"title":"LLM down","status":502,"code":"LLM_UNAVAILABLE"}',
            },
          ]),
        ),
      ),
    );
    const frames: SseFrame[] = [];
    const ac = new AbortController();
    await expect(
      streamChat(CONV_ID, 'x', { signal: ac.signal, onFrame: (f) => frames.push(f) }),
    ).rejects.toMatchObject({ code: 'LLM_UNAVAILABLE', status: 502 });
    expect(frames.map((f) => f.type)).toEqual(['started', 'delta', 'error']);
  });

  test('HTTP 409 before stream rejects with CONVERSATION_FULL (no frames delivered)', async () => {
    server.use(
      http.post(URL, () =>
        HttpResponse.json(
          { title: 'Conversation full', status: 409, code: 'CONVERSATION_FULL' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const frames: SseFrame[] = [];
    const ac = new AbortController();
    await expect(
      streamChat(CONV_ID, 'x', { signal: ac.signal, onFrame: (f) => frames.push(f) }),
    ).rejects.toMatchObject({ code: 'CONVERSATION_FULL', status: 409 });
    expect(frames).toHaveLength(0);
  });

  test('HTTP 406 before stream rejects with NOT_ACCEPTABLE', async () => {
    server.use(
      http.post(URL, () =>
        HttpResponse.json(
          { title: 'Not acceptable', status: 406, code: 'NOT_ACCEPTABLE' },
          { status: 406, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const ac = new AbortController();
    await expect(
      streamChat(CONV_ID, 'x', { signal: ac.signal, onFrame: () => undefined }),
    ).rejects.toMatchObject({ code: 'NOT_ACCEPTABLE', status: 406 });
  });

  test('AbortSignal mid-stream rejects with a DOMException(AbortError)', async () => {
    const stream = controlledSseBody();
    server.use(http.post(URL, () => sseResponse(stream.body)));

    const ac = new AbortController();
    const frames: SseFrame[] = [];
    const pending = streamChat(CONV_ID, 'x', {
      signal: ac.signal,
      onFrame: (f) => frames.push(f),
    });

    // Wait until the first frame is delivered, then abort.
    stream.push({ event: 'started', data: '{"userMessageId":"u1","conversationId":"c1"}' });
    await vi.waitFor(() => expect(frames).toHaveLength(1));
    ac.abort();

    await expect(pending).rejects.toSatisfy(
      (err: unknown) => err instanceof DOMException && err.name === 'AbortError',
    );
  });

  test('unknown frame names are logged via console.warn and dropped', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    server.use(
      http.post(URL, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"c1"}' },
            { event: 'mystery', data: '{}' },
            {
              event: 'completed',
              data: '{"assistantMessageId":"a1","title":null,"messageCount":4}',
            },
          ]),
        ),
      ),
    );
    const frames: SseFrame[] = [];
    await streamChat(CONV_ID, 'x', {
      signal: new AbortController().signal,
      onFrame: (f) => frames.push(f),
    });
    expect(frames.map((f) => f.type)).toEqual(['started', 'completed']);
    expect(warnSpy).toHaveBeenCalled();
  });

  test('stream closing without `completed` rejects with INTERNAL_ERROR', async () => {
    server.use(
      http.post(URL, () =>
        sseResponse(
          sseBody([
            { event: 'started', data: '{"userMessageId":"u1","conversationId":"c1"}' },
            { event: 'delta', data: '{"text":"truncated"}' },
          ]),
        ),
      ),
    );
    const frames: SseFrame[] = [];
    await expect(
      streamChat(CONV_ID, 'x', {
        signal: new AbortController().signal,
        onFrame: (f) => frames.push(f),
      }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(frames.map((f) => f.type)).toEqual(['started', 'delta']);
  });

  test('Authorization header is read fresh from tokenStorage on every call', async () => {
    const seen: string[] = [];
    server.use(
      http.post(URL, ({ request }) => {
        seen.push(request.headers.get('Authorization') ?? '');
        return sseResponse(
          sseBody([
            {
              event: 'completed',
              data: '{"assistantMessageId":"a1","title":null,"messageCount":1}',
            },
          ]),
        );
      }),
    );
    tokenStorage.set({
      token: 'token-A',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      mustChangePassword: false,
    });
    await streamChat(CONV_ID, 'x', {
      signal: new AbortController().signal,
      onFrame: () => undefined,
    });
    tokenStorage.set({
      token: 'token-B',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      mustChangePassword: false,
    });
    await streamChat(CONV_ID, 'x', {
      signal: new AbortController().signal,
      onFrame: () => undefined,
    });
    expect(seen).toEqual(['Bearer token-A', 'Bearer token-B']);
  });
});
