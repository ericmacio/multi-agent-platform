import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import './styles/globals.css';
import { App } from './App';
import { queryClient } from './shared/api/queryClient';
import { AuthProvider } from './shared/auth/AuthContext';
import { tokenStorage } from './shared/auth/tokenStorage';
import { ErrorBoundary } from './shared/layout/ErrorBoundary';

// Cold-start hand-off: restore an in-memory token from sessionStorage if the
// user reloaded mid-session (SW-DESIGN §6.4). MUST run before AuthProvider
// mounts so its initial state already reflects the hydrated bundle.
tokenStorage.hydrateFromSession();

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element #root not found in index.html');
}

// ErrorBoundary wraps the whole subtree — outside the providers so a
// provider throwing during initial render is caught, inside StrictMode so
// dev-mode double invocation still exercises the boundary (SW-DESIGN §10.3).
ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>,
);
