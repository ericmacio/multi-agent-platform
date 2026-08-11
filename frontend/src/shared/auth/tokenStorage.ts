import { z } from 'zod';

/**
 * The shape returned by `POST /auth/login` (minus `tokenType`). Persisted
 * intact so the bundle survives a tab reload via the sessionStorage hand-off.
 */
export type TokenBundle = {
  token: string;
  expiresAt: string;
  mustChangePassword: boolean;
};

const SESSION_KEY = 'mam.token';

// Lightweight shape check used by `hydrateFromSession` to reject corrupted
// payloads (e.g., a user manually editing sessionStorage). Loud-on-malformed:
// a partial bundle is discarded rather than partially trusted.
const bundleSchema = z.object({
  token: z.string().min(1),
  expiresAt: z.string().min(1),
  mustChangePassword: z.boolean(),
});

let memory: TokenBundle | null = null;

/**
 * In-memory primary store + sessionStorage one-shot hand-off (SW-DESIGN §6.4).
 * NOT a React context — consumed by the HTTP middleware (`client.ts`), the
 * `AuthContext` provider, and `main.tsx`'s hydration step.
 */
export const tokenStorage = {
  get(): TokenBundle | null {
    return memory;
  },

  set(bundle: TokenBundle): void {
    memory = bundle;
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(bundle));
    } catch {
      // sessionStorage may throw in privacy modes / quota errors — the
      // in-memory copy is still authoritative for the current tab.
    }
  },

  clear(): void {
    memory = null;
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      // ignore
    }
  },

  /**
   * Cold-start hand-off from a tab reload: read once from sessionStorage,
   * write into memory, and delete the sessionStorage entry so the token
   * never lives in two places at once.
   *
   * Returns the hydrated bundle (or null if nothing was stored / the payload
   * was malformed).
   */
  hydrateFromSession(): TokenBundle | null {
    let raw: string | null;
    try {
      raw = sessionStorage.getItem(SESSION_KEY);
    } catch {
      return null;
    }
    if (!raw) return null;
    // Consume the entry regardless of validity — leaving a malformed blob
    // around would just fail again on the next boot.
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      // ignore
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return null;
    }
    const result = bundleSchema.safeParse(parsed);
    if (!result.success) return null;
    memory = result.data;
    return result.data;
  },
};
