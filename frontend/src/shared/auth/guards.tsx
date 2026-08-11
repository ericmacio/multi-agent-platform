import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth, type Principal } from './AuthContext';

type GuardProps = { children?: ReactNode };

/**
 * Resolve the body to render: either the explicit `children` prop or the
 * router `<Outlet />` when the guard is used as a layout route element.
 */
function renderBody(children: ReactNode | undefined): JSX.Element {
  return <>{children ?? <Outlet />}</>;
}

function buildLoginRedirect(pathname: string, search: string): string {
  const here = pathname + search;
  if (!here || here === '/login') return '/login';
  return `/login?next=${encodeURIComponent(here)}`;
}

/**
 * Requires an authenticated, unexpired token. Redirects unauthenticated users
 * to `/login?next=<currentPath>`. The local expiry check is a belt-and-suspenders
 * guard against a window where the HTTP middleware hasn't yet observed the
 * expiry; the server is still the authority on every request.
 */
export function RequireAuth({ children }: GuardProps): JSX.Element {
  const { token, expiresAt } = useAuth();
  const location = useLocation();
  if (token === null) {
    return <Navigate to={buildLoginRedirect(location.pathname, location.search)} replace />;
  }
  if (expiresAt && Date.parse(expiresAt) < Date.now()) {
    return <Navigate to={buildLoginRedirect(location.pathname, location.search)} replace />;
  }
  return renderBody(children);
}

/**
 * Requires the authenticated principal to have a specific role. Pairs with
 * `<RequireAuth>` (which it does NOT call internally — the route table is
 * expected to compose them). Redirects to `/403` on mismatch.
 */
export function RequireRole({
  role,
  children,
}: GuardProps & { role: Principal['role'] }): JSX.Element {
  const { principal } = useAuth();
  if (principal?.role !== role) {
    return <Navigate to="/403" replace />;
  }
  return renderBody(children);
}

/**
 * Blocks every protected page when `mustChangePassword === true`. Applied to
 * every protected route EXCEPT `/change-password` itself and the logout
 * action (per SW-DESIGN §5.2).
 */
export function RequireFreshPassword({ children }: GuardProps): JSX.Element {
  const { mustChangePassword } = useAuth();
  if (mustChangePassword) {
    return <Navigate to="/change-password?reason=forced" replace />;
  }
  return renderBody(children);
}

/**
 * Inverse of `<RequireAuth>` — used on `/login` to bounce an already-authed
 * user to the dashboard.
 */
export function RequireGuest({ children }: GuardProps): JSX.Element {
  const { token } = useAuth();
  if (token !== null) {
    return <Navigate to="/" replace />;
  }
  return renderBody(children);
}
