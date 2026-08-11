# Multi-Agent Platform — Frontend

Frontend for the multi-agent platform. The single source of truth for the REST + SSE
contract is the project-root [`openapi.yaml`](../openapi.yaml). The frontend never restates
the contract — it generates a typed client from it.

## Getting started

```sh
# 1. Pin Node to the version this project is built for (20 LTS).
nvm use            # reads .nvmrc

# 2. Copy the example env file. Defaults are fine for local dev against the local backend.
cp .env.example .env.local      # macOS/Linux
copy .env.example .env.local    # Windows cmd
# edit .env.local if you need to point at a non-local backend

# 3. Install dependencies (lockfile is checked in).
npm install

# 4. Regenerate the typed API client from ../openapi.yaml.
npm run gen:api

# 5a. Run the dev server.
npm run dev
# open http://localhost:5173

# 5b. ...or run the full toolchain in one command.
npm run verify
```

The dev server proxies `/api` → `http://localhost:8080` (see `vite.config.ts`), so the
default `VITE_API_BASE_URL=http://localhost:8080/api/v1` works against a backend running
locally without any CORS configuration.

## Scripts

| Script                  | What it does                                                  |
| ----------------------- | ------------------------------------------------------------- |
| `npm run dev`           | Vite dev server on `http://localhost:5173`                    |
| `npm run build`         | TypeScript build (`tsc -b`) + Vite production build → `dist/` |
| `npm run preview`       | Serve `dist/` for local QA                                    |
| `npm run lint`          | ESLint (zero warnings)                                        |
| `npm run format`        | Prettier check (non-mutating)                                 |
| `npm run format:fix`    | Prettier write                                                |
| `npm run test`          | Vitest in watch mode                                          |
| `npm run test:run`      | Vitest single run                                             |
| `npm run test:ui`       | Vitest UI                                                     |
| `npm run gen:api`       | Regenerate `src/generated/schema.d.ts` from `../openapi.yaml` |
| `npm run gen:api:check` | Fail if the committed generated types are stale               |
| `npm run verify`        | `gen:api:check` + `lint` + `format` + `test:run` + `build`    |

`npm run verify` is the **single command** CI runs and the **single command** contributors
run before committing. It exists so every toolchain piece from EPIC-01 is exercised together
on every change.

## Documentation

- [`design/SW-DESIGN.md`](./design/SW-DESIGN.md) — software design of the frontend.
- [`backlog/EPICS.md`](./backlog/EPICS.md) — EPIC plan. One `EPIC-<nn>-US.md` file per EPIC
  details the underlying user stories.
- [`CLAUDE.md`](./CLAUDE.md) — Claude Code conventions for this module.

## Project layout (high level)

```
frontend/
├── src/
│   ├── generated/   # ⚠ generated from ../openapi.yaml — do not edit
│   ├── shared/      # cross-cutting primitives (added in EPIC-02)
│   ├── features/    # one folder per bounded business area (added per feature EPIC)
│   ├── pages/       # React Router routes — composition only
│   ├── styles/      # design tokens + global stylesheet
│   ├── test/        # MSW server, render helper, factories
│   ├── env.ts       # the ONLY file allowed to read import.meta.env
│   ├── main.tsx
│   └── App.tsx
├── scripts/         # build-time Node scripts (gen:api freshness check, ...)
└── ...config files
```

The architectural rule is `pages → features → shared → generated` (lower layers never
import from higher layers). It is enforced by an ESLint zone rule in `.eslintrc.cjs`.
