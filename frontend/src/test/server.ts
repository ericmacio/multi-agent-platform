import { setupServer } from 'msw/node';

/**
 * Empty MSW server. Feature EPICs register handlers per test via `server.use(...)`.
 * Lifecycle (`listen` / `resetHandlers` / `close`) is wired in `src/test/setup.ts`.
 */
export const server = setupServer();
