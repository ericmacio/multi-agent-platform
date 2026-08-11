import { z } from 'zod';
import { err, ok, type Result } from '@/shared/lib/result';

/**
 * Schema for the JWT payload the backend issues (REQ-AUTH-002 / REQ-AUTH-003).
 * The decode is for **UI gating only** (per SW-DESIGN §6.2): the signature is
 * NOT verified here — every protected request is authenticated by the backend.
 * The schema's loud-on-malformed behavior catches a backend shape regression
 * (e.g., a missing `role`) at the boundary rather than letting it leak as an
 * undefined role badge.
 */
export const jwtPayloadSchema = z.object({
  sub: z.string().min(1),
  role: z.enum(['ADMIN', 'STANDARD']),
  exp: z.number().int().positive(),
  iat: z.number().int().positive(),
  jti: z.string().min(1),
});

export type JwtPayload = z.infer<typeof jwtPayloadSchema>;

export type JwtDecodeError = {
  reason: 'malformed' | 'invalid-payload';
  cause?: unknown;
};

function base64UrlDecodeToString(input: string): string {
  let s = input.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4 !== 0) s += '=';
  const binary = atob(s);
  // Decode the binary string as UTF-8 — payloads may contain non-ASCII
  // characters (e.g., an email local-part with extended Latin).
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Decode the payload segment of a JWT and validate it against the platform
 * contract. Returns a `Result` rather than throwing — the auth middleware and
 * `AuthContext` both prefer typed handling over try/catch for this hot path.
 */
export function decodeJwtPayload(token: string): Result<JwtPayload, JwtDecodeError> {
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) {
    return err({ reason: 'malformed' });
  }
  let raw: unknown;
  try {
    raw = JSON.parse(base64UrlDecodeToString(parts[1]));
  } catch (cause) {
    return err({ reason: 'malformed', cause });
  }
  const parsed = jwtPayloadSchema.safeParse(raw);
  if (!parsed.success) {
    return err({ reason: 'invalid-payload', cause: parsed.error });
  }
  return ok(parsed.data);
}
