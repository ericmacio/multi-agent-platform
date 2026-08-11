import { Outlet } from 'react-router-dom';
import { AuthRedirector } from '@/shared/auth/AuthContext';
import { OfflineBanner } from '@/shared/ui/OfflineBanner';
import { ToastViewport } from '@/shared/ui/Toast';
import { ErrorBoundary } from './ErrorBoundary';
import { ExpiryBanner } from './ExpiryBanner';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

/**
 * Layout for every authenticated route. 240 px sidebar on the left; 56 px
 * topbar across the top of the right column; routed `<Outlet />` fills the
 * remaining cell. `ToastViewport` is mounted once outside the outlet so it
 * survives route transitions; the `ErrorBoundary` wraps the outlet so a
 * render-time crash inside a feature page doesn't take down the whole shell.
 */
export function AppShell(): JSX.Element {
  return (
    <div className="grid min-h-screen grid-cols-[240px_1fr] grid-rows-[auto_1fr] bg-bg-base">
      <div className="row-span-2">
        <Sidebar />
      </div>
      <Topbar />
      <main className="flex flex-col overflow-auto">
        <OfflineBanner />
        <ExpiryBanner />
        <ErrorBoundary>
          <Outlet />
        </ErrorBoundary>
      </main>
      <AuthRedirector />
      <ToastViewport />
    </div>
  );
}
