# JKXT V2.0 Deployment Runbook

## Scope

JKXT V2.0 supports two delivery modes with the same Spring Boot build:

| Mode | Setting | Intended Use | Tenant Behavior |
| --- | --- | --- | --- |
| Private | `DEPLOY_MODE=private`, `JKXT_DEPLOY_MODE=private` | Single-school private install on one host | Fixed org id, one visible school, external SMS disabled by default |
| SaaS | `DEPLOY_MODE=saas`, `JKXT_DEPLOY_MODE=saas` | Cloud multi-tenant operation | Multi-tenant admin surface, session tenant context, guarded `X-Tenant-Id` switching for `SUPER_ADMIN` |

The authoritative API prefix remains `/api/v1`. LLM provider keys stay server-side only.
For domestic hosted LLM access, the recommended low-latency OpenAI-compatible base URL is `https://api.llm-token.cn/v1`; `https://api.llm-token.cn` is suitable only for tooling that appends `/v1` itself.

## Private One-Command Startup

From `D:\VB\JKXT\JKXT V2.0`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\init-private.ps1
```

This creates `.env.private` from `.env.private.example` when missing, then starts:

- `jkxt-private-postgres` on `${PRIVATE_POSTGRES_PORT:-15432}`
- `jkxt-private-redis` on `${PRIVATE_REDIS_PORT:-16379}`
- `jkxt-private-app` on `${PRIVATE_BACKEND_PORT:-18081}`
- `jkxt-private-nginx` on `${PRIVATE_HTTP_PORT:-18180}`

Open:

```text
http://127.0.0.1:18180/Dashboard.html
```

Super admin uses a separate login URL:

```text
http://127.0.0.1:18180/Dashboard.html?portal=super
```

UAT demo accounts:

| Role | Username | Password |
| --- | --- | --- |
| Tenant test admin | `tenant.test` | `Tenant001!!!` |
| School admin (legacy demo) | `jwc.demo` | `Demo@2025` |
| Super admin | `admin` | `Super001!!!` |
| Local operator / super admin (legacy demo) | `ops.demo` | `Demo@2025` |

Before production handoff, edit `.env.private` and change:

- `POSTGRES_PASSWORD`
- `JKXT_AUTH_CAPTCHA_DEBUG=false`
- `JKXT_AUTH_COOKIE_SECURE=true` when HTTPS is terminated before the app
- `JKXT_LLM_BASE_URL` / `JKXT_LLM_API_KEY` only when a private OpenAI-compatible endpoint or an approved hosted gateway is allowed

## Private Smoke Verification

Run after the Nginx healthcheck is passing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\private-smoke.ps1
```

The smoke verifies:

1. `/api/v1/health`
2. Demo admin login
3. Quick task creation
4. Sample teacher/classroom/exam uploads
5. Import validation
6. Schedule job completion
7. Assignment, fairness, and XLSX export

Expected terminal summary contains `jobStatus: succeeded`, `assignments > 0`, `fairness: 5`, `xlsxBytes > 200`, and `pdfBytes > 20`.

## Public Single-Host Startup

Use this path when the app must be reachable from a real HTTPS domain on one public Docker host.

Files:

- `.env.public.example`
- `docker-compose.public.yml`
- `deploy/caddy/Caddyfile`
- `scripts/deploy/init-public.ps1`
- `scripts/deploy/backup-public.ps1`
- `scripts/deploy/stop-public.ps1`
- `scripts/deploy/build-public-release.ps1`
- `scripts/deploy/preflight-public-host.ps1`

Build a transferable release bundle from the current workspace before copying files to the host:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\build-public-release.ps1
```

The bundle is written under `.codex-supervisor\releases\`, includes a `release-manifest.json`, and excludes real `.env.public`, `.env.private`, `.env.saas`, and `backend\target`.

Self-test the zip before handoff or upload:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-public-release.ps1 -SkipNetwork
```

The CircleCI workflow includes a `public-release-readiness` job that builds a `-SkipUploads` release package, self-tests the actual release zip with bundled preflight, runs `preflight-public-host.ps1` with manifest hash verification, checks that `request-production-t3-approval.ps1` stays blocked against CI-only dummy values with `live-release-match=blocked` present in the approval matrix, verifies that the Vercel rewrite planner cannot become production-ready without a healthy `BackendOrigin`, and validates a draft Vercel connected config with `test-vercel-connected-config.ps1`. The release package includes the CI readiness files (`.circleci/config.yml` and `.circleci/public-preflight.env`) plus `.vercel/project.json` so the handoff artifact can be rechecked against the intended Vercel project without copying production secrets. `.vercel/project.json` contains project/team identifiers only, not tokens or secret values. This catches package drift, zip portability regressions, T3 approval or live-release-match bypasses, malformed Vercel rewrites, and static Vercel rewrite regressions in CI without deploying or using production secrets.

Prepare the production env file on the host:

```powershell
Copy-Item .env.public.example .env.public
notepad .env.public
```

Change the domain, app URL, CORS origin, and database password before startup. The script rejects example domains, placeholder secrets, captcha debug mode, and insecure cookies.

The frontend release includes local runtime vendor scripts under `assets/vendor/` for Tailwind CDN runtime, Chart.js, SheetJS, React, ReactDOM, and Babel Standalone. `Dashboard.html` must not reference external `script src` URLs in production packages; the public preflight checks that these local files and manifest entries are present. The remote browser UI smoke also observes real browser script requests and fails if any executable script loads from a different origin.

Run the redacted cutover readiness report first to confirm the selected public URL, backend origin, local `.env.public`, release bundle, and Vercel rewrite state before attempting any outward action:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-production-cutover-readiness.ps1 -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

The report writes JSON and Markdown under `.codex-supervisor\cutover-readiness\`. Secret-like env keys are reported only as present/non-empty/placeholder checks, never as raw values. For a lower-level target discovery JSON, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\discover-production-target.ps1 -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

For the connector-oriented capability report covering Vercel, Supabase, CircleCI, Expo/EAS, CodeRabbit, Jam, Browser verification, local CLIs, and the release bundle, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\discover-delivery-capabilities.ps1 -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -AllowIncomplete
```

Generate a non-deploying production cutover approval pack next. It collects the safe gate evidence, redacted env status, required T3 approvals, and an inert command checklist under `.codex-supervisor\production-cutover-pack\`. For the `vercel-rewrite` route, the pack also generates a review-only connected Vercel config and validates it with `test-vercel-connected-config.ps1` before any apply/deploy step:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\prepare-production-cutover-pack.ps1 -Route single-host -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

Generate the final delivery evidence envelope before declaring handoff complete. It maps the original delivery goal to concrete evidence items and marks each requirement as `proven`, `blocked`, `missing`, or `skipped`. The matrix also verifies that the Vercel handoff preserves `.vercel/project.json` project-link metadata in the release bundle and that the live `Dashboard.html` entrypoint matches the audited release bundle:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 -Route single-host -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -BrowserSmoke -AllowIncomplete
```

Assert the final evidence before declaring delivery complete. This command must pass without `-AllowIncomplete`; if it fails, the public target is still a no-go:

```powershell
$evidence = powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 -Route single-host -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -BrowserSmoke -BusinessSmoke -AllowRemoteDataWrites -AllowIncomplete | ConvertFrom-Json
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\assert-final-delivery-evidence.ps1 -EvidenceJson $evidence.reportJson
```

For a known backend-disconnected preview, run a separate static-preview diagnostic. This proves the UI visibly says it is a static preview / backend-disconnected target, but it does not count as production completion:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-final-delivery-evidence.ps1 -Route vercel-rewrite -AppUrl https://jkxt-responsive-v2.vercel.app -EnvFile .circleci\public-preflight.env -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -StaticPreviewSmoke -AllowIncomplete
```

When the approval ticket, target owner, rollback owner, and smoke window are known, generate the read-only T3 approval request. Its approval matrix includes release integrity, live release match, production env, approval pack, public API health, frontend/backend connectivity, and no-unapproved-T3 evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\request-production-t3-approval.ps1 -Route single-host -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -ApprovalTicket <CHANGE_ID> -TargetOwner <NAME> -RollbackOwner <NAME> -SmokeWindow <YYYY-MM-DDTHH:mmZ/YYYY-MM-DDTHH:mmZ> -AllowIncomplete
```

For the Vercel frontend plus external backend route, generate a non-deploying rewrite plan before applying `vercel.json` or deploying:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\plan-vercel-backend-rewrite.ps1 -AppUrl https://jkxt-responsive-v2.vercel.app -BackendOrigin https://<PUBLIC_BACKEND_ORIGIN>
```

The rewrite plan treats the current Vercel `/api/v1/health` 404 as a nonblocking observation; the selected backend origin must satisfy the backend contract (`/api/v1/health` returns `status=UP` and `/api/v1/auth/captcha` returns a real, non-local `captchaId`) before a proposed config is generated.

Generate and validate a review-only Vercel config before applying anything to `vercel.json`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-backend-origin-contract.ps1 -BackendOrigin https://<PUBLIC_BACKEND_ORIGIN>
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 -BackendOrigin https://<PUBLIC_BACKEND_ORIGIN> -OutputPath .\.codex-supervisor\vercel.connected.review.json
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-vercel-connected-config.ps1 -ConfigPath .\.codex-supervisor\vercel.connected.review.json -BackendOrigin https://<PUBLIC_BACKEND_ORIGIN>
```

Run the launch gate orchestrator in safe mode to execute discovery, release self-test, host preflight, and `init-public.ps1 -DryRun` as one JSON-producing gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip
```

Only after explicit T3 approval, start production through the same orchestrator with the confirmation phrase:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 -AppUrl https://<PUBLIC_DOMAIN> -BackendOrigin https://<PUBLIC_DOMAIN> -EnvFile .env.public -ReleaseZipPath .\.codex-supervisor\releases\jkxt-public-public-20260707-r54.zip -StartProduction -ConfirmStartProduction "START JKXT PUBLIC PRODUCTION"
```

Before go-live, verify the public stop/rollback path without stopping anything:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 -EnvFile .env.public -DryRun
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 -EnvFile .env.public -DryRun
```

After the public stack is started and before approved data-write smoke, create a public database backup with an explicit confirmation phrase. This writes a SQL dump plus `.sha256` checksum into the `jkxt-public-backups` named volume:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 -EnvFile .env.public -ConfirmBackupProduction "BACKUP JKXT PUBLIC PRODUCTION"
```

If rollback or public shutdown is explicitly approved, use the matching stop confirmation phrase. This runs `docker compose stop`, preserves named volumes, and does not delete production data:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 -EnvFile .env.public -ConfirmStopProduction "STOP JKXT PUBLIC PRODUCTION"
```

Run the public host preflight before startup:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\preflight-public-host.ps1 -RequireManifest -VerifyManifestHashes
```

For a local/offline dry run before DNS is ready, add `-SkipNetwork`. On the real host, do not skip the network check unless DNS is intentionally managed outside that host workflow.

Validate without starting containers. The command returns JSON evidence and performs no T3 action:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\init-public.ps1 -DryRun
```

Start only after DNS points to the host, ports 80/443 are open, and the T3 approval request is ready. Direct startup also requires the confirmation phrase and returns JSON evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\init-public.ps1 -ConfirmStartProduction "START JKXT PUBLIC PRODUCTION"
```

Then verify the public URL:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-connected-remote.ps1 -AppUrl https://<PUBLIC_DOMAIN> -SkipLogin
```

Run the remote browser UI smoke before handoff. This opens the deployed Dashboard in Chrome/Playwright, observes the backend captcha API and browser script requests, and fails if the page still shows static-preview, backend-disconnected, `Failed to fetch`, or executable scripts from a different origin:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-browser-ui.ps1 -AppUrl https://<PUBLIC_DOMAIN> -SkipLogin
```

For staging or UAT targets with captcha debug enabled, omit `-SkipLogin` to exercise login and the quick scheduling page. For production with captcha debug disabled, keep `-SkipLogin` and complete a manual operator login check.

After approving test data writes to the target environment, run the full remote business smoke. This creates a task, imports workbook data, runs scheduling, and downloads XLSX/PDF exports:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-business-smoke.ps1 -AppUrl https://<PUBLIC_DOMAIN> -AllowRemoteDataWrites
```

Run the completion audit before handoff:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 -AppUrl https://<PUBLIC_DOMAIN> -SkipLogin -RequirePublicEnv -BrowserSmoke
```

When the target is approved for automated business smoke data, include it in the audit:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 -AppUrl https://<PUBLIC_DOMAIN> -RequirePublicEnv -BrowserSmoke -BusinessSmoke -AllowRemoteDataWrites
```

## SaaS Configuration

Use `.env.saas.example` as the deployment template. SaaS mode should normally run behind managed TLS and externalized PostgreSQL/Redis:

```text
DEPLOY_MODE=saas
JKXT_DEPLOY_MODE=saas
SPRING_PROFILES_ACTIVE=saas
JKXT_AUTH_COOKIE_SECURE=true
```

Recommended SaaS LLM gateway values when provider-backed AI is enabled:

```text
JKXT_LLM_ENABLED=true
JKXT_LLM_ACCEPTANCE_MODE=true
JKXT_LLM_PROVIDER=gpt-agent
JKXT_LLM_BASE_URL=https://api.llm-token.cn/v1
JKXT_LLM_FALLBACK_BASE_URL=https://gpt-agent.cc/v1
JKXT_LLM_API_KEY=<server-side-secret>
JKXT_LLM_MODEL=claude-sonnet-4-6
JKXT_LLM_MAX_RETRIES=2
```

SaaS tenant context defaults to the authenticated backend session and server-side RBAC. `X-Tenant-Id` is accepted only in SaaS mode for authenticated `SUPER_ADMIN` users; normal school admins cannot use the header to cross tenants. Browser-only `localStorage` tenant hints are not trusted for authorization.

Current P12 header contract:

| Header | Allowed Caller | Behavior |
| --- | --- | --- |
| none | Any authenticated user | Uses the session organization id. |
| `X-Tenant-Id=<uuid>` | `SUPER_ADMIN` only, SaaS mode only | Sets `TenantContext` for tenant-scoped APIs and echoes the selected tenant id in the response header when the tenant exists and is active. |
| `X-Tenant-Id=<uuid>` | Private mode or non-super user | Rejected with 403; invalid UUID is rejected with 400; unavailable tenant UUID is rejected with 404. |

## Backup And Restore Drill

Create a private backup inside the `jkxt-private-backups` compose volume:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-private.ps1
```

The script writes:

```text
/backups/jkxt-private-YYYYMMDD-HHMMSS.sql
/backups/jkxt-private-YYYYMMDD-HHMMSS.sql.sha256
```

Run a restore drill into a scratch database:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\restore-drill-private.ps1
```

Expected drill output includes row counts for:

- `organization`
- `app_user`
- `exam_task`
- `schedule_assignment`

The drill verifies `/backups/*.sql.sha256` before restoring into the scratch database.

Optional scratch cleanup:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\restore-drill-private.ps1 -DropScratchAfter
```

Drill record template:

| Item | Value |
| --- | --- |
| Backup file | `/backups/jkxt-private-...sql` |
| Checksum file | `/backups/jkxt-private-...sql.sha256` |
| Restore target | `jkxt_restore_drill` |
| Verified counts | organization/app_user/exam_task/schedule_assignment |
| RPO target | <= 1 hour |
| RTO target | <= 4 hours |
| Operator | Fill during UAT |
| Result | Pass / Blocked |

## Operational Notes

- Private compose intentionally avoids Kubernetes, cloud object storage, and external queue components.
- PostgreSQL 16 and Redis are the only stateful runtime dependencies.
- Nginx serves the existing static UI and proxies `/api/v1` to the app.
- Private mode exposes one fixed org through platform tenant/account lists even when SaaS demo seed data exists in the database.
- SaaS mode keeps tenant switching server-guarded through authenticated session plus `SUPER_ADMIN`-only `X-Tenant-Id`; do not rely on frontend storage for authorization.
- Do not commit `.env.private`, real LLM keys, or customer secrets.










