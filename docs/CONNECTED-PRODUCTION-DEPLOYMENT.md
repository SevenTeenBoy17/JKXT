# JKXT Connected Production Deployment Contract

## Current State

- Local fullstack is verified through `http://127.0.0.1:18180`.
- The current Vercel production alias serves the static frontend, but `/api/v1/health` returns 404.
- A connected production release is not valid until the public app URL can reach a real backend at `/api/v1`.

## Required Production Shape

Use one of these shapes:

1. Preferred private install: one public host runs Docker Compose with nginx, Spring Boot, PostgreSQL, and Redis. TLS terminates before nginx, and nginx serves `Dashboard.html` plus proxies `/api/v1` to the app.
2. Vercel frontend plus external backend: Vercel serves static assets and rewrites `/api/v1/:path*` to a separately hosted HTTPS backend origin.
3. Vercel container/function exploration: only after confirming external PostgreSQL and Redis, function duration, upload size, and job lifecycle constraints. Do not treat this as ready just because a container image can be stored.

## Public Single-Host Docker Package

The repository includes a production-oriented public host template:

- `.env.public.example`
- `docker-compose.public.yml`
- `deploy/caddy/Caddyfile`
- `scripts/deploy/init-public.ps1`
- `scripts/deploy/backup-public.ps1`
- `scripts/deploy/stop-public.ps1`
- `scripts/deploy/build-public-release.ps1`
- `scripts/deploy/preflight-public-host.ps1`

This shape runs PostgreSQL and Redis without host port exposure, serves the existing static UI through nginx, proxies `/api/v1` to the Spring Boot app, and exposes only Caddy on ports 80 and 443.

Build the host-transferable release bundle first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\build-public-release.ps1
```

The bundle is written under `.codex-supervisor\releases\`, contains `release-manifest.json` with SHA-256 hashes, and excludes real `.env.public`, `.env.private`, `.env.saas`, and `backend\target`.

Self-test the zip before copying it to a host:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-public-release.ps1 -SkipNetwork
```

CI also runs a non-deploying `public-release-readiness` job. It builds a `-SkipUploads` release package, self-tests the actual release zip with bundled preflight, runs `preflight-public-host.ps1` with manifest hash verification, verifies that `request-production-t3-approval.ps1` remains blocked against CI-only dummy env values with `live-release-match=blocked` present in the approval matrix, checks that the Vercel rewrite planner cannot become production-ready without a healthy `BackendOrigin`, and validates a draft Vercel connected config with `test-vercel-connected-config.ps1`. The release package includes the CI readiness files (`.circleci/config.yml` and `.circleci/public-preflight.env`) plus `.vercel/project.json` so the handoff artifact can be rechecked against the intended Vercel project without copying production secrets. `.vercel/project.json` contains project/team identifiers only, not tokens or secret values. This catches package drift, zip portability regressions, accidental T3 approval or live-release-match bypasses, malformed Vercel rewrites, and static Vercel rewrite regressions before a host handoff.

On the public host:

```powershell
Copy-Item .env.public.example .env.public
notepad .env.public
```

Before startup, change at least:

- `PUBLIC_DOMAIN`
- `PUBLIC_APP_URL`
- `APP_BASE_URL`
- `CORS_ALLOWED_ORIGINS`
- `POSTGRES_PASSWORD`

Run preflight before starting containers:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\preflight-public-host.ps1 `
  -RequireManifest `
  -VerifyManifestHashes
```

For local dry runs before DNS is delegated, add `-SkipNetwork`. For the production host, the normal run should prove DNS resolution, Docker Compose config, Caddy config, required files, env guardrails, and release-manifest hashes before startup.

The frontend runtime scripts are packaged locally under `assets/vendor/` for Tailwind CDN runtime, Chart.js, SheetJS, React, ReactDOM, and Babel Standalone. `Dashboard.html` should not load external `script src` URLs on the public host; preflight enforces the local vendor files and manifest entries. The remote browser UI smoke observes actual script requests and fails if any executable script loads from a different origin.

Run the redacted cutover readiness report before any outward production action. It is read-only and reports whether the chosen app URL has `/api/v1/health`, whether `.env.public` is complete, whether Vercel needs an API rewrite, and whether the release bundle contains the deploy verifiers. Secret-like env keys are reported only as present/non-empty/placeholder checks, never as raw values:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-production-cutover-readiness.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

For a lower-level target discovery JSON, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\discover-production-target.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

Run the delivery capability discovery gate when you need the platform-by-platform picture requested for this project. It covers Vercel linkage, Supabase local/CLI state, CircleCI config, Expo/EAS applicability, CodeRabbit/Jam availability, browser verifier scripts, and release bundle contents:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\discover-delivery-capabilities.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -AllowIncomplete
```

Prepare a non-deploying production cutover approval pack before asking for any T3 action. It runs the safe gates, writes JSON/Markdown evidence, and creates an inert command checklist with a top-level refusal guard. For `-Route vercel-rewrite`, the pack also generates a review-only connected Vercel config and validates the draft shape before any apply/deploy step:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\prepare-production-cutover-pack.ps1 `
  -Route single-host `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

Generate the final delivery evidence envelope before declaring the release complete. It maps the requested browser, CI, review, Supabase/Vercel/Expo/Jam/CodeRabbit lanes, Vercel project-link metadata, runtime smoke requirements, and live `Dashboard.html` release-entrypoint match to concrete evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 `
  -Route single-host `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -BrowserSmoke `
  -AllowIncomplete
```

Assert the final evidence before declaring delivery complete. This command must pass without `-AllowIncomplete`; if it fails, the public target is still a no-go:

```powershell
$evidence = powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 `
  -Route single-host `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -BrowserSmoke `
  -BusinessSmoke `
  -AllowRemoteDataWrites `
  -AllowIncomplete | ConvertFrom-Json
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\assert-final-delivery-evidence.ps1 -EvidenceJson $evidence.reportJson
```

For a known backend-disconnected preview, run a separate static-preview diagnostic. It proves the UI visibly says it is a static preview / backend-disconnected target, but it does not count as production completion:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 `
  -Route vercel-rewrite `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -EnvFile .circleci\public-preflight.env `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -StaticPreviewSmoke `
  -AllowIncomplete
```

Generate the production T3 approval request when the target owner, rollback owner, approval ticket, and smoke window are known. This is still read-only; it creates JSON/Markdown evidence and an approval phrase, but does not approve or execute the T3 action. The approval matrix includes release integrity, live release match, production env, approval pack, public API health, frontend/backend connectivity, and no-unapproved-T3 evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\request-production-t3-approval.ps1 `
  -Route single-host `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -ApprovalTicket <CHANGE_ID> `
  -TargetOwner <NAME> `
  -RollbackOwner <NAME> `
  -SmokeWindow <YYYY-MM-DDTHH:mmZ/YYYY-MM-DDTHH:mmZ> `
  -AllowIncomplete
```

Run the launch gate orchestrator in safe mode next. It wraps discovery, release ZIP self-test, host preflight, and `init-public.ps1 -DryRun` into one JSON report:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

Only after explicit T3 approval, the same orchestrator can start production. It refuses to start unless the confirmation phrase matches exactly:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -BackendOrigin https://<PUBLIC_DOMAIN> `
  -EnvFile .env.public `
  -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip `
  -StartProduction `
  -ConfirmStartProduction "START JKXT PUBLIC PRODUCTION"
```

Before go-live, prove the rollback/stop command path. In dry-run mode it validates Compose and prints the exact stop command without stopping anything:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 `
  -EnvFile .env.public `
  -DryRun

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 `
  -EnvFile .env.public `
  -DryRun
```

After the public stack is started and before approved data-write smoke, create a public database backup with an explicit confirmation phrase. This writes a SQL dump plus `.sha256` checksum into the `jkxt-public-backups` named volume:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 `
  -EnvFile .env.public `
  -ConfirmBackupProduction "BACKUP JKXT PUBLIC PRODUCTION"
```

If rollback or public shutdown is explicitly approved, use the matching stop confirmation phrase. The command runs `docker compose stop`, preserves named volumes, and never deletes database, Redis, Caddy, or backup volumes:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 `
  -EnvFile .env.public `
  -ConfirmStopProduction "STOP JKXT PUBLIC PRODUCTION"
```

Production guardrails enforced by `init-public.ps1`:

- example domain is rejected
- placeholder secrets are rejected
- `JKXT_AUTH_CAPTCHA_DEBUG=true` is rejected
- `JKXT_AUTH_COOKIE_SECURE` must be true

Dry-run config validation. This returns JSON evidence and performs no T3 action:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\init-public.ps1 -DryRun
```

Start the public stack only after DNS points `PUBLIC_DOMAIN` to the host, ports 80/443 are open, and the T3 approval request is ready. Direct startup also requires the confirmation phrase and returns JSON evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\init-public.ps1 `
  -ConfirmStartProduction "START JKXT PUBLIC PRODUCTION"
```

Then verify:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-connected-remote.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -SkipLogin
```

Run the browser UI smoke next. It opens the deployed Dashboard, observes the backend captcha API and browser script requests, and fails if the UI still shows static-preview, backend-disconnected, `Failed to fetch`, or executable scripts from a different origin:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-browser-ui.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -SkipLogin
```

For staging/UAT with captcha debug enabled, omit `-SkipLogin` to exercise login and the quick scheduling page. For production with captcha debug disabled, keep `-SkipLogin` and complete a manual operator login check.

After approving test data writes to that environment, run the full remote business smoke. It creates a task, imports teachers/classrooms/exams, runs scheduling, and downloads XLSX/PDF exports:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-business-smoke.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -AllowRemoteDataWrites
```

Before handoff, run the production readiness audit. It fails until the remote app is connected and the local release evidence is intact:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -SkipLogin `
  -RequirePublicEnv `
  -BrowserSmoke
```

For an approved staging/UAT/production smoke window, include the full business smoke in the audit:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 `
  -AppUrl https://<PUBLIC_DOMAIN> `
  -RequirePublicEnv `
  -BrowserSmoke `
  -BusinessSmoke `
  -AllowRemoteDataWrites
```

## Vercel External Rewrite Contract

Vercel supports external-origin rewrites, so a request such as `/api/v1/health` can be proxied to `https://backend.example.com/api/v1/health` while the browser stays on the Vercel domain.

Generate a non-deploying rewrite plan first. This verifies the selected backend origin, writes a proposed config and Markdown report under `.codex-supervisor/vercel-connected-plan/`, and keeps production apply/deploy approval-gated:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\plan-vercel-backend-rewrite.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -BackendOrigin https://backend.example.com
```

If the current Vercel app still returns 404 for `/api/v1/health`, the plan records that as an observational `current:api-health` skip. Proposed config generation is blocked by the selected `BackendOrigin` being missing, invalid, non-public/non-HTTPS for production, not returning `status=UP`, or not returning a real non-local `captchaId` from `/api/v1/auth/captcha`.

Generate a draft connected config after the backend is reachable:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-backend-origin-contract.ps1 `
  -BackendOrigin https://backend.example.com
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 `
  -BackendOrigin https://backend.example.com `
  -OutputPath .\.codex-supervisor\vercel.connected.review.json
```

This writes a review-only candidate only after probing `https://backend.example.com/api/v1/health` and `https://backend.example.com/api/v1/auth/captcha`. Validate the candidate before applying it to `vercel.json`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-vercel-connected-config.ps1 `
  -ConfigPath .\.codex-supervisor\vercel.connected.review.json `
  -BackendOrigin https://backend.example.com
```

After review, apply it to `vercel.json`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 `
  -BackendOrigin https://backend.example.com `
  -Apply
```

Then deploy with the approved Vercel flow and verify the production alias:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-connected-remote.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app
```

For production with captcha debug disabled, use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-connected-remote.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -SkipLogin
```

Then run the browser UI smoke and complete a manual browser login check with a real operator account:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-browser-ui.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -SkipLogin
```

The same completion audit can be pointed at the Vercel alias after the approved backend rewrite is live:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -SkipLogin `
  -BrowserSmoke `
  -SkipReleaseBundle
```

If the Vercel-backed target is approved for automated test data writes, run the full business smoke against the alias before handoff:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-business-smoke.ps1 `
  -AppUrl https://jkxt-responsive-v2.vercel.app `
  -AllowRemoteDataWrites
```

## Release Gate

A connected production deployment is complete only when all are true:

- `GET https://<app>/api/v1/health` returns `status=UP`.
- `GET https://<app>/Dashboard.html` returns the production frontend.
- Login reaches the real backend and `GET /api/v1/auth/me` returns the authenticated user.
- `verify-remote-browser-ui.ps1` passes: Dashboard renders in a browser, the backend captcha endpoint is observed, no static-preview/backend-disconnected/`Failed to fetch` text is visible, and optional UAT login reaches the dashboard plus quick scheduling page.
- `verify-remote-business-smoke.ps1` passes after approved test data writes: it creates a task, uploads teacher/classroom/exam data, validates, runs scheduling, returns assignments, and exports xlsx/pdf.
- No real secret is present in frontend assets, Vercel static files, screenshots, or committed docs.
- Rollback is known: revert Vercel alias to the previous static deployment or remove the `/api/v1` rewrite.

## Guardrails

- Do not deploy another static-only Vercel build as a connected release.
- Do not point Vercel rewrites at localhost, a temporary tunnel, or a private LAN address for production.
- Do not enable `JKXT_AUTH_CAPTCHA_DEBUG=true` for production go-live; keep it only for staging/UAT automation.
- Do not commit `.env.private`, `.env.saas`, API keys, database passwords, or customer data.










