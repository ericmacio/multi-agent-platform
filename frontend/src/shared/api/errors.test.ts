import { describe, expect, test } from 'vitest';
import { ApiError, normalizeResponse, type ProblemCode } from './errors';

function problemResponse(
  status: number,
  body: Record<string, unknown>,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  });
}

// One entry per known `ProblemCode`. The status column mirrors the openapi
// responses that emit each code (the response normalizer doesn't depend on it,
// but exercising the realistic pairing is the contract the rest of the stack
// will observe).
const PER_CODE_CASES: Array<{ code: ProblemCode; status: number }> = [
  { code: 'VALIDATION_ERROR', status: 400 },
  { code: 'INVALID_CREDENTIALS', status: 401 },
  { code: 'MUST_CHANGE_PASSWORD', status: 403 },
  { code: 'FORBIDDEN', status: 403 },
  { code: 'NOT_FOUND', status: 404 },
  { code: 'METHOD_NOT_ALLOWED', status: 405 },
  { code: 'CONFLICT', status: 409 },
  { code: 'DUPLICATE_AGENT_NAME', status: 409 },
  { code: 'NESTED_TEAM_FORBIDDEN', status: 409 },
  { code: 'CROSS_OWNER_TEAM_MEMBER', status: 409 },
  { code: 'CONVERSATION_FULL', status: 409 },
  { code: 'RATE_LIMITED', status: 429 },
  { code: 'LLM_UNAVAILABLE', status: 502 },
  { code: 'MCP_SERVER_ERROR', status: 502 },
  { code: 'NOT_ACCEPTABLE', status: 406 },
  { code: 'INTERNAL_ERROR', status: 500 },
];

describe('normalizeResponse', () => {
  test.each(PER_CODE_CASES)(
    'maps $code ($status) to an ApiError with the matching discriminator',
    async ({ code, status }) => {
      const r = problemResponse(status, {
        type: `https://errors.example/${code.toLowerCase()}`,
        title: 'fixture title',
        status,
        detail: 'fixture detail',
        code,
      });
      const err = await normalizeResponse(r);
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe(code);
      expect(err.status).toBe(status);
      expect(err.title).toBe('fixture title');
      expect(err.detail).toBe('fixture detail');
      expect(err.type).toBe(`https://errors.example/${code.toLowerCase()}`);
      expect(err.fieldErrors).toEqual({});
    },
  );

  test('falls back to __unknown__ for codes outside the current enum', async () => {
    const r = problemResponse(418, {
      title: 'I am a teapot',
      status: 418,
      code: 'FUTURE_CODE_X',
    });
    const err = await normalizeResponse(r);
    expect(err.code).toBe('__unknown__');
    expect(err.status).toBe(418);
    expect(err.title).toBe('I am a teapot');
  });

  test('returns INTERNAL_ERROR with `cause` set on malformed JSON', async () => {
    const r = new Response('not json at all', {
      status: 500,
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await normalizeResponse(r);
    expect(err.code).toBe('INTERNAL_ERROR');
    expect(err.detail).toBe('Malformed error response');
    expect(err.cause).toBeInstanceOf(SyntaxError);
  });

  test('returns INTERNAL_ERROR (no cause) when the body parses to a non-object', async () => {
    // e.g., a server emitting `[]` or `"oops"` for an error body.
    const r = new Response(JSON.stringify([]), {
      status: 500,
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await normalizeResponse(r);
    expect(err.code).toBe('INTERNAL_ERROR');
    expect(err.detail).toBe('Malformed error response');
    expect(err.cause).toBeUndefined();
  });

  test('parses an integer Retry-After header on RATE_LIMITED', async () => {
    const r = problemResponse(
      429,
      {
        title: 'Too many requests',
        status: 429,
        code: 'RATE_LIMITED',
      },
      { 'Retry-After': '30' },
    );
    const err = await normalizeResponse(r);
    expect(err.code).toBe('RATE_LIMITED');
    expect(err.retryAfterSeconds).toBe(30);
  });

  test('ignores a non-integer Retry-After value', async () => {
    const r = problemResponse(
      429,
      {
        title: 'Too many requests',
        status: 429,
        code: 'RATE_LIMITED',
      },
      { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' },
    );
    const err = await normalizeResponse(r);
    expect(err.retryAfterSeconds).toBeUndefined();
  });

  test('builds fieldErrors from a VALIDATION_ERROR with multiple errors[]', async () => {
    const r = problemResponse(400, {
      title: 'Validation error',
      status: 400,
      code: 'VALIDATION_ERROR',
      errors: [
        { field: 'name', message: 'must be at most 32 characters' },
        { field: 'email', message: 'must be a valid email' },
      ],
    });
    const err = await normalizeResponse(r);
    expect(err.code).toBe('VALIDATION_ERROR');
    expect(err.fieldErrors).toEqual({
      name: 'must be at most 32 characters',
      email: 'must be a valid email',
    });
  });

  test('a duplicate field entry produces a last-wins map', async () => {
    const r = problemResponse(400, {
      title: 'Validation error',
      status: 400,
      code: 'VALIDATION_ERROR',
      errors: [
        { field: 'name', message: 'first' },
        { field: 'name', message: 'second' },
      ],
    });
    const err = await normalizeResponse(r);
    expect(err.fieldErrors).toEqual({ name: 'second' });
  });

  test('tolerates a missing body and falls back to a non-empty title', async () => {
    const r = new Response('', {
      status: 500,
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await normalizeResponse(r);
    expect(err.code).toBe('INTERNAL_ERROR');
    expect(err.title.length).toBeGreaterThan(0);
  });

  test('preserves the optional `instance` field', async () => {
    const r = problemResponse(404, {
      title: 'Not found',
      status: 404,
      code: 'NOT_FOUND',
      instance: '/api/v1/agents/abc',
    });
    const err = await normalizeResponse(r);
    expect(err.instance).toBe('/api/v1/agents/abc');
  });
});

describe('ApiError', () => {
  test('exposes a readable toString()', () => {
    const err = new ApiError({
      status: 409,
      code: 'DUPLICATE_AGENT_NAME',
      title: 'Duplicate agent name',
      detail: 'Pick another name.',
    });
    expect(err.toString()).toBe('DUPLICATE_AGENT_NAME: Duplicate agent name — Pick another name.');
  });

  test('toString() omits the dash when no detail is set', () => {
    const err = new ApiError({
      status: 404,
      code: 'NOT_FOUND',
      title: 'Not found',
    });
    expect(err.toString()).toBe('NOT_FOUND: Not found');
  });

  test('synthesized() builds an error with the default copy title', () => {
    const err = ApiError.synthesized('INVALID_CREDENTIALS', 401);
    expect(err.code).toBe('INVALID_CREDENTIALS');
    expect(err.status).toBe(401);
    expect(err.title.length).toBeGreaterThan(0);
    expect(err.fieldErrors).toEqual({});
  });

  test('is a real Error subclass (instanceof + Error.message wired)', () => {
    const err = new ApiError({
      status: 500,
      code: 'INTERNAL_ERROR',
      title: 'Internal error',
      detail: 'oops',
    });
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toBe('oops');
  });
});
