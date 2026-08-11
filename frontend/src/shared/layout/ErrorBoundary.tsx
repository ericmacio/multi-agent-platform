import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from '@/shared/ui/Button';
import { EmptyState } from '@/shared/ui/EmptyState';
import { AlertTriangle } from '@/shared/ui/icons';

type Props = { children: ReactNode };
type State = { hasError: boolean };

/**
 * Catches React render-time exceptions (including from a hook that throws an
 * unhandled `ApiError`) and renders the minimalist fallback. No remote
 * logging in v1 (SW-DESIGN §16.3); errors land in `console.error` so dev
 * sessions stay informative.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('Root render error caught by ErrorBoundary', error, info);
  }

  override render(): ReactNode {
    if (!this.state.hasError) return this.props.children;
    return (
      <div className="flex min-h-screen items-center justify-center bg-bg-base">
        <EmptyState
          icon={<AlertTriangle aria-hidden />}
          title="We hit an unexpected error"
          description="Please reload the page. If the problem persists, contact your administrator."
          action={<Button onClick={() => window.location.reload()}>Reload</Button>}
        />
      </div>
    );
  }
}
