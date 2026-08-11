import { LoginForm } from '@/features/auth/LoginForm';

/**
 * The `/login` route. Composition only — `AuthShell` (provided by the route
 * layout in US-03-007) renders the centered card and the `ToastViewport`.
 */
export function LoginPage(): JSX.Element {
  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-center text-xl font-medium text-text-primary">Sign in</h1>
      <LoginForm />
    </div>
  );
}
