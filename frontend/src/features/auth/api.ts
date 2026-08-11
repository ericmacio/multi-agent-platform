import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { ApiError } from '@/shared/api/errors';
import { useAuth } from '@/shared/auth/AuthContext';
import type { components } from '@/generated/schema';
import { invalidateCatalogs } from '@/features/catalog/api';

export type LoginRequest = components['schemas']['LoginRequest'];
export type LoginResponse = components['schemas']['LoginResponse'];
export type ChangePasswordRequest = components['schemas']['ChangePasswordRequest'];

/**
 * `POST /auth/login` — submits the credentials and, on success, calls
 * `signIn(...)` to populate the `AuthContext`. The form picks the
 * navigation destination after considering `?next=`, so this hook does NOT
 * navigate.
 *
 * Error surface: `mutation.error` is the standard `ApiError` shape. The form
 * decides how to render each code (e.g., `INVALID_CREDENTIALS` → generic
 * form-level alert per `REQ-AUTH-009`; `RATE_LIMITED` → countdown).
 */
export function useLogin(): UseMutationResult<LoginResponse, ApiError, LoginRequest> {
  const { signIn } = useAuth();
  return useMutation<LoginResponse, ApiError, LoginRequest>({
    mutationFn: async (body) => {
      const { data } = await api.POST('/auth/login', { body });
      // The error middleware throws on non-2xx, so a resolved call always
      // yields `data` here. The `!` keeps the return type narrow.
      return data!;
    },
    onSuccess: (data) => {
      signIn({
        token: data.token,
        expiresAt: data.expiresAt,
        mustChangePassword: data.mustChangePassword,
      });
    },
  });
}

/**
 * `POST /auth/logout` — best-effort. A 401, 5xx, or network failure must NOT
 * block local cleanup: the server-side denylist is a soft guarantee, and the
 * user's intent ("sign out") is honored locally regardless. `signOut` runs in
 * `onSettled` so it fires on both the success and the swallowed-error paths.
 */
export function useLogout(): UseMutationResult<void, ApiError, void> {
  const { signOut } = useAuth();
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, void>({
    mutationFn: async () => {
      try {
        await api.POST('/auth/logout', {});
      } catch {
        // Swallowed by design — see the contract above. The local state is
        // cleared in `onSettled` regardless.
      }
    },
    onSettled: () => {
      // Evict catalog caches so a new user landing in the same tab doesn't
      // observe the previous user's snapshot (US-04-001).
      invalidateCatalogs(queryClient);
      void signOut('/login');
    },
  });
}

/**
 * `PUT /auth/password` — changes the authenticated user's password. On
 * success, the JWT remains valid (per the openapi note) and we only clear
 * the local `mustChangePassword` flag. We do NOT force a re-login.
 *
 * A `400 VALIDATION_ERROR` with `field='currentPassword'` is the canonical
 * "wrong current password" response (the backend uses this rather than a
 * 401 — see US-03-002 / US-03-004 contract notes). A real 401 here means
 * the JWT itself was rejected by the server, and the shared error
 * middleware will already have cleared the session.
 */
export function useChangeOwnPassword(): UseMutationResult<void, ApiError, ChangePasswordRequest> {
  const { setMustChangePassword } = useAuth();
  return useMutation<void, ApiError, ChangePasswordRequest>({
    mutationFn: async (body) => {
      await api.PUT('/auth/password', { body });
      // 204 No Content; nothing to return.
    },
    onSuccess: () => {
      setMustChangePassword(false);
    },
  });
}
