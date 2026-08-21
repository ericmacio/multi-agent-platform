# Deploy the frontend on AWS S3 + CloudFront

## Context

The frontend is a Vite + React + TypeScript SPA that builds to a static `dist/` bundle. There is currently **no deployment configuration** for it (no Dockerfile, no CI pipeline, no IaC). The goal is to host the built SPA on AWS S3 fronted by CloudFront so that:
- The bundle is served over HTTPS from a globally-cached CDN.
- The backend keeps running on its own host (already accessible at `http://15.237.213.41:8080` per `.env.local`).

**Feasibility check (from exploration):**
- ✅ `vite.config.ts` produces a plain static `dist/` — S3-compatible; no SSR.
- ✅ Router is `createBrowserRouter` — requires a SPA fallback (CloudFront custom error response).
- ✅ API base URL is injected at build time via `VITE_API_BASE_URL` (read in `src/env.ts`, consumed in `src/shared/api/client.ts`) — no code changes needed to switch backends.
- ✅ Backend CORS is property-driven (`app.cors.allowed-origins` in `SpringSecurityConfig`); already exposes `Retry-After`, `X-Correlation-Id` and accepts `Authorization`, `X-Client-Id`, `X-Api-Key`, `X-Correlation-Id`.
- ✅ SSE endpoint (`POST /conversations/{id}/messages`, `text/event-stream`, 10-min emitter timeout) will be reached **directly** by the browser (separate-origin mode), so CloudFront's origin timeout limits do not apply.
- ⚠️ Backend rate limiting is a single global Bucket4j bucket (no per-IP keying). Putting the frontend on CloudFront does not change this — informational only, no action.

**Decisions:**
- **Separate origin + CORS** — CloudFront serves only the SPA; browser calls the backend host directly.
- **Terraform** for infrastructure.
- **Default `*.cloudfront.net` domain** — no ACM cert / custom domain in this iteration.

## Target architecture

```
Browser
  ├── HTTPS GET *   → CloudFront (dxxxxx.cloudfront.net)
  │                     └── OAC → S3 bucket (private) → dist/*
  │                     └── 403/404 → /index.html (SPA fallback)
  │
  └── HTTPS *      → Backend host (VITE_API_BASE_URL, e.g. https://api.host/api/v1)
                       CORS: allowed-origins includes https://dxxxxx.cloudfront.net
```

Two independent HTTPS origins. No API traffic transits CloudFront.

## Work items

### 1. Build-time environment file
- Add `frontend/.env.production` (gitignored) with `VITE_API_BASE_URL=<https backend URL>`. The bundle produced by `npm run build` will bake this in.
- Extend `frontend/.env.example` to document `VITE_API_BASE_URL` for production builds.
- No changes required to `vite.config.ts`, `src/env.ts`, or `src/shared/api/client.ts`.

### 2. Terraform module — `frontend/infra/`
New directory containing:

| File | Purpose |
|---|---|
| `versions.tf` | Pin `terraform` ≥ 1.6 and `hashicorp/aws` ≥ 5.0. Region locked to `us-east-1` for the CloudFront-attached resources (viewer cert requirement even if unused). Bucket may live elsewhere. |
| `variables.tf` | Inputs: `project`, `environment`, `bucket_name`, `default_root_object` (`index.html`), `tags`. |
| `main.tf` | Resources listed below. |
| `outputs.tf` | `cloudfront_domain_name`, `cloudfront_distribution_id`, `s3_bucket_name`. |
| `README.md` | Bootstrap steps: `terraform init`, `plan`, `apply`, then feed outputs into the deploy script + backend CORS config. |

**Resources:**
- `aws_s3_bucket` — private, versioning enabled, `bucket_key_enabled = true`, SSE-S3 default encryption.
- `aws_s3_bucket_public_access_block` — block all public access.
- `aws_s3_bucket_policy` — grant `s3:GetObject` to the CloudFront OAC principal only.
- `aws_cloudfront_origin_access_control` — signing behavior `always`, protocol `sigv4`.
- `aws_cloudfront_response_headers_policy` — security headers (HSTS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, minimal CSP that allows the backend origin for `connect-src`).
- `aws_cloudfront_distribution`:
  - Single S3 origin using the OAC.
  - `default_cache_behavior`: `viewer_protocol_policy = redirect-to-https`, allowed methods `[GET, HEAD]`, `compress = true`, AWS-managed `CachingOptimized` cache policy, AWS-managed `CORS-S3Origin` origin-request policy, the response-headers policy above.
  - Two `custom_error_response` entries: `403 → /index.html` (200), `404 → /index.html` (200) — this is what makes deep-linked SPA routes reload correctly.
  - `price_class = PriceClass_100` (US + EU edges — sufficient given target audience).
  - `viewer_certificate { cloudfront_default_certificate = true }`.
  - No aliases (custom domain deferred).

### 3. Deploy script — `frontend/scripts/deploy.ps1`
PowerShell (Windows-no-admin environment). Inputs from environment variables or CLI args: `BUCKET_NAME`, `DISTRIBUTION_ID`.

Steps:
1. `npm ci`
2. `npm run build` (picks up `.env.production`).
3. `aws s3 sync dist/ s3://$BUCKET_NAME/ --delete --exclude "index.html" --cache-control "public,max-age=31536000,immutable"` — hashed assets are immutable.
4. `aws s3 cp dist/index.html s3://$BUCKET_NAME/index.html --cache-control "no-cache,no-store,must-revalidate" --content-type "text/html"` — never cache the SPA shell.
5. `aws cloudfront create-invalidation --distribution-id $DISTRIBUTION_ID --paths "/index.html" "/"` — bust the shell.

Assumes the caller has `aws` CLI configured (SSO / profile / env vars).

### 4. Backend CORS update
Not a code change — a configuration change on the backend deployment. After Terraform outputs `cloudfront_domain_name`, add `https://<domain>.cloudfront.net` to the backend's `app.cors.allowed-origins` (via env var override — no source change needed).

**Bootstrap order** (one-time chicken-and-egg):
1. `terraform apply` → obtain CloudFront domain.
2. Update backend deployment's `APP_CORS_ALLOWED_ORIGINS` env var to include the new domain; restart backend.
3. Write `frontend/.env.production` with the backend's public URL.
4. Run `frontend/scripts/deploy.ps1`.

## Critical files (created / touched)

| Path | Action |
|---|---|
| `frontend/.env.production` | **New** (gitignored) — sets `VITE_API_BASE_URL`. |
| `frontend/.env.example` | **Edit** — document the production var. |
| `frontend/.gitignore` | **Verify** `.env.production` is covered by existing `*.local` / `.env` patterns; add if missing. |
| `frontend/infra/versions.tf` | **New** |
| `frontend/infra/variables.tf` | **New** |
| `frontend/infra/main.tf` | **New** |
| `frontend/infra/outputs.tf` | **New** |
| `frontend/infra/README.md` | **New** |
| `frontend/scripts/deploy.ps1` | **New** |
| Backend deployment env — `APP_CORS_ALLOWED_ORIGINS` | **Edit** (out-of-repo, deployment-side). |

No changes needed in: `vite.config.ts`, `src/env.ts`, `src/shared/api/client.ts`, `src/pages/routes.tsx`, or any component code.

## Verification

**Local pre-deploy:**
1. `npm run build` succeeds with `VITE_API_BASE_URL` pointing at the backend.
2. `npm run preview` on `http://localhost:4173` — smoke-check login → chat → SSE stream.

**Terraform:**
1. `terraform validate` and `terraform plan` are clean.
2. `terraform apply` completes; outputs printed.

**Post-deploy end-to-end (against `https://<domain>.cloudfront.net`):**
1. `curl -I https://<domain>.cloudfront.net/` returns `200`, `content-type: text/html`, `cache-control: no-cache,...`.
2. `curl -I https://<domain>.cloudfront.net/assets/index-<hash>.js` returns `200` with `cache-control: public, max-age=31536000, immutable`.
3. Load the URL in a browser → SPA loads, no console errors.
4. Log in — confirms browser can call the backend + CORS preflight passes.
5. Open a chat, send a message — confirms SSE stream is received (Network tab shows `text/event-stream` with delta frames).
6. Reload directly on a deep link (e.g. `/agents/<id>`) → SPA reloads correctly (proves the 403/404 → `/index.html` custom error responses).
7. Log out → confirms `POST /auth/logout` succeeds (proves `Authorization` header CORS-allowed).

**Rollback:** re-run `deploy.ps1` against a prior build artifact, or `terraform destroy` (retains no data — bucket is versioned so previous object versions can be restored).
