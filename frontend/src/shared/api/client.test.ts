import { afterEach, beforeEach, describe, expect, test, vi, type MockInstance } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { tokenStorage, type TokenBundle } from '@/shared/auth/tokenStorage';
import { ApiError } from './errors';
import { api, unwrap } from './client';

const BASE = 'http://localhost:8080/api/v1';

function base64url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Hand-built JWT. The header & signature are decorative — the auth middleware
 * only decodes the payload segment and never verifies the signature.
 */
function makeJwt(payload: {
  sub?: string;
  role?: 'ADMIN' | 'STANDARD';
  exp: number;
  iat?: number;
  jti?: string;
}): string {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64url(
    JSON.stringify({
      sub: payload.sub ?? 'alice@example.com',
      role: payload.role ?? 'STANDARD',
      iat: payload.iat ?? Math.floor(Date.now() / 1000),
      jti: payload.jti ?? 'jti-1',
      exp: payload.exp,
    }),
  );
  return `${header}.${body}.sig`;
}

function liveBundle(): TokenBundle {
  const exp = Math.floor(Date.now() / 1000) + 60;
  return {
    token: makeJwt({ exp }),
    expiresAt: new Date(exp * 1000).toISOString(),
    mustChangePassword: false,
  };
}

function expiredBundle(): TokenBundle {
  const exp = Math.floor(Date.now() / 1000) - 60;
  return {
    token: makeJwt({ exp }),
    expiresAt: new Date(exp * 1000).toISOString(),
    mustChangePassword: false,
  };
}

/** Force the inferred return type of `.catch()` over an openapi-fetch call to ApiError. */
async function expectApiError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    if (e instanceof ApiError) return e;
    throw e;
  }
  throw new Error('Expected the call to reject with an ApiError, but it resolved.');
}

describe('api client', () => {
  let dispatchSpy: MockInstance<[event: Event], boolean>;
  let clearSpy: MockInstance<[], void>;

  beforeEach(() => {
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
    clearSpy = vi.spyOn(tokenStorage, 'clear');
    tokenStorage.clear();
    clearSpy.mockClear();
  });

  afterEach(() => {
    dispatchSpy.mockRestore();
    clearSpy.mockRestore();
    tokenStorage.clear();
  });

  test('200 responses resolve with typed data', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json({ items: [{ name: 'aws-s3', description: 'S3' }] }),
      ),
    );
    const result = await api.GET('/tools');
    expect(result.error).toBeUndefined();
    expect(result.data).toEqual({ items: [{ name: 'aws-s3', description: 'S3' }] });
  });

  test('400 VALIDATION_ERROR rejects with a populated fieldErrors map', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          {
            type: 'https://errors.example/validation',
            title: 'Validation error',
            status: 400,
            detail: 'Bad input',
            code: 'VALIDATION_ERROR',
            errors: [{ field: 'email', message: 'invalid' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const err = await expectApiError(
      api.POST('/auth/login', { body: { email: '', password: 'x' } }),
    );
    expect(err.code).toBe('VALIDATION_ERROR');
    expect(err.fieldErrors).toEqual({ email: 'invalid' });
  });

  test('401 dispatches auth:logout and clears the token', async () => {
    tokenStorage.set(liveBundle());
    clearSpy.mockClear();
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json(
          { title: 'Invalid credentials', status: 401, code: 'INVALID_CREDENTIALS' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    await expect(api.GET('/tools')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
      status: 401,
    });
    expect(clearSpy).toHaveBeenCalledTimes(1);
    const dispatched = dispatchSpy.mock.calls.find(
      ([ev]) => ev instanceof CustomEvent && ev.type === 'auth:logout',
    );
    expect(dispatched).toBeDefined();
    expect((dispatched![0] as CustomEvent).detail).toEqual({ reason: 'token-rejected' });
  });

  test('429 propagates Retry-After; no auth-logout side effect', async () => {
    server.use(
      http.get(`${BASE}/tools`, () =>
        HttpResponse.json(
          { title: 'Too many requests', status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' },
          },
        ),
      ),
    );
    const err = await expectApiError(api.GET('/tools'));
    expect(err.code).toBe('RATE_LIMITED');
    expect(err.retryAfterSeconds).toBe(5);
    expect(clearSpy).not.toHaveBeenCalled();
    const dispatched = dispatchSpy.mock.calls.find(
      ([ev]) => ev instanceof CustomEvent && ev.type === 'auth:logout',
    );
    expect(dispatched).toBeUndefined();
  });

  test('expired JWT short-circuits without hitting the network', async () => {
    let networkHits = 0;
    server.use(
      http.get(`${BASE}/tools`, () => {
        networkHits += 1;
        return HttpResponse.json({ items: [] });
      }),
    );
    tokenStorage.set(expiredBundle());
    clearSpy.mockClear();

    const err = await expectApiError(api.GET('/tools'));
    expect(err.code).toBe('INVALID_CREDENTIALS');
    expect(err.status).toBe(401);
    expect(networkHits).toBe(0);
    expect(clearSpy).toHaveBeenCalledTimes(1);
    const dispatched = dispatchSpy.mock.calls.find(
      ([ev]) => ev instanceof CustomEvent && ev.type === 'auth:logout',
    );
    expect(dispatched).toBeDefined();
    expect((dispatched![0] as CustomEvent).detail).toEqual({ reason: 'token-expired' });
  });

  test('malformed JWT in storage short-circuits the same way as expired', async () => {
    let networkHits = 0;
    server.use(
      http.get(`${BASE}/tools`, () => {
        networkHits += 1;
        return HttpResponse.json({ items: [] });
      }),
    );
    tokenStorage.set({
      token: 'not-a-jwt',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      mustChangePassword: false,
    });
    clearSpy.mockClear();

    const err = await expectApiError(api.GET('/tools'));
    expect(err.code).toBe('INVALID_CREDENTIALS');
    expect(networkHits).toBe(0);
    expect(clearSpy).toHaveBeenCalledTimes(1);
  });

  test('anonymous requests send no Authorization header', async () => {
    let receivedAuth: string | null = 'unset';
    server.use(
      http.post(`${BASE}/auth/login`, ({ request }) => {
        receivedAuth = request.headers.get('Authorization');
        return HttpResponse.json({
          token: 't',
          tokenType: 'Bearer' as const,
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          mustChangePassword: false,
        });
      }),
    );
    await api.POST('/auth/login', { body: { email: 'a@b.c', password: 'x' } });
    expect(receivedAuth).toBeNull();
  });

  test('valid JWT is injected as a Bearer Authorization header', async () => {
    const bundle = liveBundle();
    tokenStorage.set(bundle);
    let receivedAuth: string | null = null;
    server.use(
      http.get(`${BASE}/tools`, ({ request }) => {
        receivedAuth = request.headers.get('Authorization');
        return HttpResponse.json({ items: [] });
      }),
    );
    await api.GET('/tools');
    expect(receivedAuth).toBe(`Bearer ${bundle.token}`);
  });

  test('POST request bodies are JSON-serialized with the correct Content-Type', async () => {
    let receivedBody: unknown;
    let receivedContentType: string | null = null;
    server.use(
      http.post(`${BASE}/auth/login`, async ({ request }) => {
        receivedContentType = request.headers.get('Content-Type');
        receivedBody = await request.json();
        return HttpResponse.json({
          token: 't',
          tokenType: 'Bearer' as const,
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          mustChangePassword: false,
        });
      }),
    );
    await api.POST('/auth/login', { body: { email: 'alice@example.com', password: 'Hunter2!aa' } });
    expect(receivedContentType).toBe('application/json');
    expect(receivedBody).toEqual({ email: 'alice@example.com', password: 'Hunter2!aa' });
  });

  test('unwrap() returns data on success', () => {
    expect(unwrap({ data: { items: [] } })).toEqual({ items: [] });
  });

  test('unwrap() throws if `error` is set (defensive against middleware regression)', () => {
    const err = new ApiError({ status: 500, code: 'INTERNAL_ERROR', title: 'oops' });
    expect(() => unwrap({ error: err })).toThrow(err);
  });
});
