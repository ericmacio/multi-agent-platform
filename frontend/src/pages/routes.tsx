import { Suspense, lazy } from 'react';
import { createBrowserRouter, Outlet, type RouteObject } from 'react-router-dom';
import { RequireAuth, RequireFreshPassword, RequireGuest, RequireRole } from '@/shared/auth/guards';
import { AppShell } from '@/shared/layout/AppShell';
import { AuthShell } from '@/shared/layout/AuthShell';
import { Spinner } from '@/shared/ui/Spinner';
import { ChangePasswordPage } from './ChangePasswordPage';
import { ForbiddenPage } from './ForbiddenPage';
import { LoginPage } from './LoginPage';
import { NotFoundPlaceholder } from './NotFoundPlaceholder';

// Lazy-loaded feature pages — SW-DESIGN §16.1: everything outside auth + chat
// is split into its own chunk.
const HomePage = lazy(() => import('./HomePage'));
const ToolsPage = lazy(() => import('./catalog/ToolsPage'));
const McpServersPage = lazy(() => import('./catalog/McpServersPage'));
const AgentsPage = lazy(() => import('./agents/AgentsPage'));
const AgentCreatePage = lazy(() => import('./agents/AgentCreatePage'));
const AgentDetailPage = lazy(() => import('./agents/AgentDetailPage'));
const AgentEditPage = lazy(() => import('./agents/AgentEditPage'));
const ChatPage = lazy(() => import('./chat/ChatPage'));
const ChatNewPage = lazy(() => import('./chat/ChatNewPage'));
const ConversationPage = lazy(() => import('./chat/ConversationPage'));
const AdminUsersPage = lazy(() => import('./admin/AdminUsersPage'));
const AdminUserCreatePage = lazy(() => import('./admin/AdminUserCreatePage'));
const AdminUserDetailPage = lazy(() => import('./admin/AdminUserDetailPage'));
const AdminApiKeysPage = lazy(() => import('./admin/AdminApiKeysPage'));
const AdminRateLimitPage = lazy(() => import('./admin/AdminRateLimitPage'));

function PageFallback(): JSX.Element {
  return (
    <div className="flex min-h-[200px] items-center justify-center">
      <Spinner size={20} label="Loading page…" />
    </div>
  );
}

function withSuspense(element: JSX.Element): JSX.Element {
  return <Suspense fallback={<PageFallback />}>{element}</Suspense>;
}

/**
 * Route definitions. Exported as a `RouteObject[]` so integration tests can
 * build a memory router from the same source. Production wires
 * `createBrowserRouter(routes)` below.
 *
 * Layout-route elements compose the guards: `/login` requires guest;
 * `/change-password` requires auth only (NOT `RequireFreshPassword`, else
 * the forced-password redirect would loop); every other route requires both
 * `RequireAuth` and `RequireFreshPassword`.
 */
export const routes: RouteObject[] = [
  // Public — no auth required. RequireRole redirects here on role mismatch.
  { path: '/403', element: <ForbiddenPage /> },
  {
    element: (
      <RequireGuest>
        <AuthShell />
      </RequireGuest>
    ),
    children: [{ path: '/login', element: <LoginPage /> }],
  },
  {
    element: (
      <RequireAuth>
        <AuthShell />
      </RequireAuth>
    ),
    children: [{ path: '/change-password', element: <ChangePasswordPage /> }],
  },
  {
    element: (
      <RequireAuth>
        <RequireFreshPassword>
          <AppShell />
        </RequireFreshPassword>
      </RequireAuth>
    ),
    children: [
      { path: '/', element: withSuspense(<HomePage />) },
      { path: '/tools', element: withSuspense(<ToolsPage />) },
      { path: '/mcp-servers', element: withSuspense(<McpServersPage />) },
      { path: '/agents', element: withSuspense(<AgentsPage />) },
      { path: '/agents/new', element: withSuspense(<AgentCreatePage />) },
      { path: '/agents/:agentId', element: withSuspense(<AgentDetailPage />) },
      { path: '/agents/:agentId/edit', element: withSuspense(<AgentEditPage />) },
      {
        path: '/chat',
        element: withSuspense(<ChatPage />),
        children: [
          { index: true, element: withSuspense(<ChatNewPage />) },
          { path: 'new', element: withSuspense(<ChatNewPage />) },
          { path: ':conversationId', element: withSuspense(<ConversationPage />) },
        ],
      },
      // Admin section — nested under RequireRole(ADMIN) so a STANDARD user
      // hitting any /admin/** deep link is bounced to /403 by the guard.
      {
        element: (
          <RequireRole role="ADMIN">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { path: '/admin/users', element: withSuspense(<AdminUsersPage />) },
          { path: '/admin/users/new', element: withSuspense(<AdminUserCreatePage />) },
          { path: '/admin/users/:userId', element: withSuspense(<AdminUserDetailPage />) },
          { path: '/admin/api-keys', element: withSuspense(<AdminApiKeysPage />) },
          { path: '/admin/rate-limit', element: withSuspense(<AdminRateLimitPage />) },
        ],
      },
      { path: '*', element: <NotFoundPlaceholder /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
