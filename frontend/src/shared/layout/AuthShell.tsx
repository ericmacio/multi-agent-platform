import { Outlet } from 'react-router-dom';
import { AuthRedirector } from '@/shared/auth/AuthContext';
import { Card } from '@/shared/ui/Card';
import { OfflineBanner } from '@/shared/ui/OfflineBanner';
import { ToastViewport } from '@/shared/ui/Toast';
import { env } from '@/env';

/**
 * Centered card layout for the auth surface (`/login`, `/change-password`).
 * Routed `<Outlet />` renders inside the card. Same `ToastViewport` singleton
 * the `AppShell` uses (so toasts on `/login` are surfaced consistently).
 */
export function AuthShell(): JSX.Element {
  return (
    <div className="flex min-h-screen flex-col bg-bg-base">
      <OfflineBanner />
      <div className="flex flex-1 items-center justify-center px-4">
        <div className="w-full max-w-sm space-y-6">
          <header className="text-center">
            <p className="text-xs uppercase tracking-wider text-text-muted">Multi-Agent</p>
            <h1 className="text-xl font-medium text-text-primary">{env.VITE_APP_NAME}</h1>
          </header>
          <Card padding="lg">
            <Outlet />
          </Card>
        </div>
      </div>
      <AuthRedirector />
      <ToastViewport />
    </div>
  );
}
