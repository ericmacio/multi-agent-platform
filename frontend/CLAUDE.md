# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in the **frontend** of this repository.

## Purpose

The frontend is the user-facing client of the multi-agent platform. It consumes the backend REST API documented in `../openapi.yaml` (project root) and provides:

- **Authentication** — login / logout / token refresh, password change.
- **User profile** — view "me", manage personal API keys.
- **Admin** — user creation and management (visible only when the authenticated user has `role = admin`).
- **Agent management** — create / list / view / edit / delete the user's own agents (configure name, system prompt, tools, MCP servers, memory size, team).
- **Chat** — start conversations with any owned agent, stream agent responses, view past conversations, rename, restart (`continue` or `fresh`), delete.

The frontend is **stateless** with respect to the business — every persistent fact lives in the backend; the frontend caches server state for UX only.


## Architecture

### Stack

| Concern | Technology |
|---|---|
| Language | **TypeScript** (strict mode) |
| UI library | **React 18+** |
| CSS / styling | **Tailwind CSS** |
| Build tool | **Vite** |
| Routing | **React Router** |
| HTTP client | `fetch` (wrapped) — generated typed client from `openapi.yaml` |
| SSE client | **`@microsoft/fetch-event-source`** (NOT the native `EventSource` — see §SSE below) |
| Testing | **Vitest** + **React Testing Library** |
| Lint / format | **ESLint** + **Prettier** |
| Package manager | **npm** (lockfile checked in) |


## Graphical Design

### Tech stack
- Framework: React 18 + TypeScript
- Styling: Tailwind CSS (extended config) + CSS variables for design tokens
- Animations: Framer Motion
- Routing: React Router v6


### Visual identity — "Clarté" theme

This application is a professional AI agent management tool. The aesthetic alternates a **clair** main workspace (Pearl background, white cards) with **sombre** navigation surfaces (Ink sidebar, chat header) to anchor the interface. No superfluous decoration.

**Guiding principle**: light content zone for reading and density; dark navigation zone for orientation; a single accent (Indigo) for CTAs. Reference: `frontend/design/charte.html`.


### Color palette

Always use CSS variables — never hardcode hex values inside components. Source of truth: `frontend/src/styles/tokens.css`.

```css
/* Backgrounds — alternating light workspace / dark navigation */
--color-bg-base: #F5F2EC;        /* Sable — page background */
--color-bg-surface: #FFFFFF;     /* Blanc — cards, panels */
--color-bg-elevated: #FFFFFF;    /* Blanc — dropdowns, tooltips, modals */
--color-bg-subtle: #EEF0FF;      /* Indigo pâle — accented surface, hover */
--color-bg-ink: #0F1B2D;         /* Encre nuit — sidebar, dark surfaces */
--color-bg-ink-2: #16263D;       /* Encre hover — nav item hover */

/* Borders */
--color-border-default: #E6E1D6; /* soft border on light surfaces */
--color-border-strong: #D6D0C2;
--color-border-ink: #1F3352;     /* separator inside dark zones */
--color-border-accent: #C7D0FF;  /* dimmed Indigo border */
--color-border-focus: #4F46E5;   /* focus ring (Indigo) */

/* Text on light surfaces */
--color-text-primary: #0F1B2D;   /* Encre — headings, strong text */
--color-text-secondary: #4A5A6A; /* Ardoise — body text, descriptions */
--color-text-muted: #8A97AB;     /* Nuage — metadata, placeholders */
--color-text-disabled: #C6CDD8;

/* Text on ink (dark) surfaces */
--color-text-on-ink: #F1F3F8;
--color-text-on-ink-2: #B8C2D3;
--color-text-on-ink-3: #7C8BA3;

/* Signature color — Indigo */
--color-accent: #4F46E5;         /* CTAs, active links, primary icons */
--color-accent-hover: #4338CA;
--color-accent-bg: #EEF0FF;      /* Indigo background on light surfaces (badges, hover) */
--color-accent-dim: #C7D0FF;     /* dimmed Indigo (borders, overlays) */
--color-accent-soft: rgba(79, 70, 229, 0.22); /* Indigo overlay on ink (nav active) */

/* Semantic */
--color-success: #059669;        /* active agent, OK status */
--color-success-bg: #E6F7EF;
--color-info: #2563EB;           /* information, blue model tags */
--color-info-bg: #E7EEFE;
--color-warning: #B45309;        /* caution, rate limit */
--color-warning-bg: #FEF3D7;
--color-danger: #B91C1C;         /* error, failed agent */
--color-danger-bg: #FDECEC;
```

**Alternation rule** — inside a page: sidebar and any chat header use `bg-ink` surfaces with `text-on-ink*` foreground; main content, topbar, cards, forms and lists use `bg-base` / `bg-surface` with `text-primary` / `text-secondary` foreground. Never mix ink and light tokens within a single container.

**Token naming note** — the "dark" family is deliberately named `ink` (not `dark`) to avoid any collision with Tailwind's built-in `dark:` variant modifier.


### Typography

Loaded via Google Fonts in `index.html` (no npm font package). Three families, three voices:

- **`Inter`** — body text, UI labels, buttons.
- **`Fraunces`** — headings (H1/H2/H3). Adds character without breaking neutrality. Available via the Tailwind class `font-voice`.
- **`JetBrains Mono`** — IDs, tokens, code blocks.

```css
--font-sans:  'Inter', system-ui, -apple-system, sans-serif;
--font-voice: 'Fraunces', ui-serif, Georgia, serif;
--font-mono:  'JetBrains Mono', 'Fira Code', monospace;
```

Headings (`<h1>` / `<h2>` / `<h3>`) automatically inherit `--font-voice` via `globals.css` — no per-component override needed.

## Style

The page should be very professional, with a modern and appealing look.


## Four canonical page states

Every list and detail page must resolve one of four states:

1. **Populated** — golden path.
2. **Empty** — `<EmptyState>` from `@/shared/ui/EmptyState` when the query resolves with zero items.
3. **Loading** — `<LoadingList>` from `@/shared/ui/LoadingList` for list pages; a page-specific `Skeleton` composition for detail pages. Never a full-page spinner on list pages.
4. **Error** — an inline `Card role="alert"` with `errorCopy[code]` copy and a Retry button. For per-resource 403 / 404, prefer the in-content `<ForbiddenState>` / `<NotFoundState>` primitives (they carry `role="alert"` and consistent copy). The route-level fallbacks `pages/ForbiddenPage` and `pages/NotFoundPlaceholder` remain for whole-route failures.

See `frontend/docs/EPIC-11-audit.md` for the per-page coverage matrix.

## Accessibility

The a11y baseline is documented in `frontend/docs/A11Y.md`. New primitives and pages should add an axe assertion using the `expectNoA11yViolations(container)` helper from `@/test/axe` on their representative DOM.

## Status

Frontend implementation has started. This document defines the conventions to follow once it does. The single source of truth for the API contract is `../openapi.yaml`; backend implementation can be found in backend folder
backlog folder contains all EPICS and user stories
backlog/US-STATUS.md, EPICS.md must be updated each time new EPICS are created of US have been implemented
