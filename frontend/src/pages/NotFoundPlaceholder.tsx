import { Link } from 'react-router-dom';

export function NotFoundPlaceholder() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-6 text-center">
      <h1 className="font-sans text-2xl font-medium text-text-primary">404 — Not found</h1>
      <p className="mt-2 font-sans text-sm text-text-secondary">
        The page you requested does not exist.
      </p>
      <Link
        to="/"
        className="mt-6 font-mono text-xs text-accent underline-offset-4 hover:underline"
      >
        Back to home
      </Link>
    </main>
  );
}
