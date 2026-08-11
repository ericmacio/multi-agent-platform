import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from '@/shared/ui/Toast';
import { decodeJwtPayload } from './jwt';
import { tokenStorage, type TokenBundle } from './tokenStorage';

export type Principal = { sub: string; role: 'ADMIN' | 'STANDARD' };

export type AuthState = {
  token: string | null;
  expiresAt: string | null;
  principal: Principal | null;
  mustChangePassword: boolean;
};

export type AuthContextValue = AuthState & {
  signIn: (bundle: TokenBundle) => void;
  /**
   * Clears local auth state and queues a navigation to `redirectAfter`
   * (default `/login`). The actual `useNavigate(...)` call happens in
   * `<AuthRedirector />` since the provider lives above the router.
   *
   * The EPIC-03 `useLogout` hook is responsible for the best-effort
   * `POST /auth/logout` request; this method only owns local state + intent.
   */
  signOut: (redirectAfter?: string) => Promise<void>;

  /**
   * Update the `mustChangePassword` gating flag while preserving the rest of
   * the bundle. Used by `useChangeOwnPassword` after a successful password
   * change (the JWT remains valid; only the gating flag clears). Also
   * persists the new bundle to `tokenStorage` so a reload doesn't resurrect
   * the old flag.
   */
  setMustChangePassword: (value: boolean) => void;

  expiryWarning: boolean;
  dismissExpiryWarning: () => void;

  /**
   * Navigation intent set by `signOut` and the `auth:logout` listener.
   * `<AuthRedirector />` consumes and clears it; nothing else should.
   */
  redirectTo: string | null;
  clearRedirect: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

const EMPTY_STATE: AuthState = {
  token: null,
  expiresAt: null,
  principal: null,
  mustChangePassword: false,
};

function stateFromBundle(bundle: TokenBundle | null): AuthState {
  if (!bundle) return EMPTY_STATE;
  const decoded = decodeJwtPayload(bundle.token);
  if (!decoded.ok) {
    // Defensive: a JWT that fails decode shouldn't have been stored. Treat as
    // logged-out rather than rendering an undefined principal.
    tokenStorage.clear();
    return EMPTY_STATE;
  }
  return {
    token: bundle.token,
    expiresAt: bundle.expiresAt,
    principal: { sub: decoded.value.sub, role: decoded.value.role },
    mustChangePassword: bundle.mustChangePassword,
  };
}

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [state, setState] = useState<AuthState>(() => stateFromBundle(tokenStorage.get()));
  const [expiryWarning, setExpiryWarning] = useState(false);
  const [redirectTo, setRedirectTo] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const cancelExpiryTimer = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const scheduleExpiryWarning = useCallback(
    (expiresAt: string | null) => {
      cancelExpiryTimer();
      setExpiryWarning(false);
      if (!expiresAt) return;
      const triggerAtMs = Date.parse(expiresAt) - 30_000;
      if (!Number.isFinite(triggerAtMs)) return;
      const delay = triggerAtMs - Date.now();
      if (delay <= 0) {
        setExpiryWarning(true);
        return;
      }
      timerRef.current = setTimeout(() => {
        setExpiryWarning(true);
        timerRef.current = null;
      }, delay);
    },
    [cancelExpiryTimer],
  );

  // Schedule the warning for the initial state (after hydration from storage).
  useEffect(() => {
    scheduleExpiryWarning(state.expiresAt);
    return cancelExpiryTimer;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only on mount
  }, []);

  const signIn = useCallback(
    (bundle: TokenBundle) => {
      const decoded = decodeJwtPayload(bundle.token);
      if (!decoded.ok) {
        throw new Error('signIn: JWT payload failed to decode — refusing to set auth state.');
      }
      tokenStorage.set(bundle);
      const next: AuthState = {
        token: bundle.token,
        expiresAt: bundle.expiresAt,
        principal: { sub: decoded.value.sub, role: decoded.value.role },
        mustChangePassword: bundle.mustChangePassword,
      };
      setState(next);
      scheduleExpiryWarning(bundle.expiresAt);
    },
    [scheduleExpiryWarning],
  );

  const signOut = useCallback(
    async (redirectAfter: string = '/login') => {
      cancelExpiryTimer();
      tokenStorage.clear();
      setState(EMPTY_STATE);
      setExpiryWarning(false);
      setRedirectTo(redirectAfter);
    },
    [cancelExpiryTimer],
  );

  const dismissExpiryWarning = useCallback(() => setExpiryWarning(false), []);
  const clearRedirect = useCallback(() => setRedirectTo(null), []);

  const setMustChangePassword = useCallback((value: boolean) => {
    setState((prev) => {
      if (prev.token === null || prev.expiresAt === null) return prev;
      // Keep the persisted bundle in sync so a tab reload preserves the flag.
      tokenStorage.set({
        token: prev.token,
        expiresAt: prev.expiresAt,
        mustChangePassword: value,
      });
      return { ...prev, mustChangePassword: value };
    });
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      signIn,
      signOut,
      setMustChangePassword,
      expiryWarning,
      dismissExpiryWarning,
      redirectTo,
      clearRedirect,
    }),
    [
      state,
      signIn,
      signOut,
      setMustChangePassword,
      expiryWarning,
      dismissExpiryWarning,
      redirectTo,
      clearRedirect,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within <AuthProvider>.');
  }
  return ctx;
}

/**
 * Mount once inside the router (e.g., as a layout-route child). Two jobs:
 *
 * 1. Reacts to the provider's `redirectTo` intent (set by `signIn` /
 *    `signOut` callers) by calling `useNavigate(...)`.
 * 2. Listens for the HTTP middleware's `auth:logout` event and triggers
 *    `signOut('/login?next=<currentPath>')` so a server-rejected token
 *    routes the user back to login with the in-flight page preserved.
 *
 * Both jobs live here (not in `AuthProvider`) because they need the router's
 * `useLocation` / `useNavigate` hooks, and `AuthProvider` is mounted above
 * the router by `main.tsx`.
 */
export function AuthRedirector(): null {
  const { redirectTo, clearRedirect, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const locationRef = useRef(location);
  locationRef.current = location;

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ reason?: string }>).detail;
      // On a real server-rejected token, surface the session-expired toast.
      // On the proactive expiry short-circuit (`token-expired`), the in-app
      // expiry banner has already warned the user; staying silent avoids a
      // redundant notification.
      if (detail?.reason === 'token-rejected') {
        toast.info('Your session expired — sign in again.', 'session-expired');
      }
      const path = locationRef.current.pathname + locationRef.current.search;
      const next = path && path !== '/login' ? `/login?next=${encodeURIComponent(path)}` : '/login';
      void signOut(next);
    };
    window.addEventListener('auth:logout', handler);
    return () => window.removeEventListener('auth:logout', handler);
  }, [signOut]);

  useEffect(() => {
    if (redirectTo === null) return;
    navigate(redirectTo, { replace: true });
    clearRedirect();
  }, [redirectTo, clearRedirect, navigate]);

  return null;
}
