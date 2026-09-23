# JKXT V2.0 Dev Runbook

Status: P1 backend skeleton available  
Workspace root: `D:\VB\JKXT\JKXT V2.0`  
Last updated: 2026-06-05

## 1. Context Gate

Before changing code in this workspace, read:

- `supervisor_runtime_rules.md`
- `.codex-supervisor/mandatory-context-gate.md`
- `CLAUDE.md`
- `docs/08-BACKEND-DEV-SPEC.md`
- `docs/09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md`
- `docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md`
- `uploads/07-UI-DEVELOPMENT-SPEC.md`

Authoritative route:

- Backend: Java 21 + Spring Boot 3.3 modular monolith.
- Database: PostgreSQL 16 + Flyway.
- Cache/job/progress: Redis.
- Scheduling: OR-Tools CP-SAT Java binding is the final scheduler.
- AI: LLM only assists with NL-to-DSL, explanation, repair suggestions, and summary.
- External API base: `/api/v1`.
- Frontend: preserve current UI; add adapter/state wiring only.

`docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md` is still useful for page-to-service mapping, but any Node/NestJS/FastAPI route in earlier material is superseded by `docs/08-BACKEND-DEV-SPEC.md`.

## 2. Current Frontend Baseline

The current app is a high-fidelity static React prototype. It has no `package.json`, no bundler, no local lint/test scripts, and no backend calls yet.

Entry point:

- `Dashboard.html`

Runtime model:

- React 18 UMD from CDN.
- ReactDOM UMD from CDN.
- Babel Standalone compiles `src/**/*.jsx` in the browser.
- Tailwind CDN runtime.
- Chart.js and SheetJS/XLSX from CDN.
- Components register globals on `window`.
- Load order in `Dashboard.html` is significant.

Current frontend startup:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"
python -m http.server 8000
```

Open:

```text
http://127.0.0.1:8000/Dashboard.html
```

Use another port only when 8000 is occupied:

```powershell
python -m http.server 4174
```

Then open:

```text
http://127.0.0.1:4174/Dashboard.html
```

Do not open `Dashboard.html` through `file://`; Babel fetches JSX over XHR, so local file loading fails under browser CORS.

Current prototype persistence:

- `localStorage.jkxt:auth`
- `localStorage.jkxt:mode`
- `localStorage.jkxt:page`
- `localStorage.jkxt:onboarded`
- `localStorage.jkxt:tasks` through `src/pages/quick-engine.jsx`

Production direction:

- `localStorage` can remain only as a development hint.
- Real auth and role recovery must come from `/api/v1/auth/login` and `/api/v1/auth/me` or equivalent server session endpoints.
- No long-lived production token, LLM key, prompt-sensitive raw data, teacher/student private data, or privacy data may be stored in the browser.

## 3. Backend Directory Plan

Use `backend/` for the Spring Boot service.

Do not use `services/api/` for P1. The approved route is a modular monolith, and `docs/10-CODEX-FULLSTACK-IMPLEMENTATION-PLAYBOOK.md` already names `backend/` for P1.

Planned P1 layout:

```text
backend/
  README.md
  pom.xml or build.gradle
  mvnw.cmd / mvnw or gradlew.bat / gradlew
  Dockerfile
  src/
    main/
      java/com/jkxt/
        JkxtBackendApplication.java
        common/
        security/
        tenant/
        org/
        masterdata/
        importer/
        scheduling/
        ai/
        tasks/
        stats/
        platform/
        notify/
        audit/
      resources/
        application.yml
        application-dev.yml
        db/migration/
    test/
      java/com/jkxt/
```

P1 now provides a runnable Maven Spring Boot project in `backend/` with health/version endpoints, OpenAPI, Actuator, and a minimal Flyway baseline marker.

Local toolchain note:

- This machine currently has Java 8 and no local Maven/JDK 21.
- `backend/mvnw.cmd` and `backend/mvnw` run Maven through Docker image `maven:3.9.9-eclipse-temurin-21`.
- Docker Desktop must be running before running backend tests or builds through the wrapper.

## 4. Docker Compose Service Plan

P1 introduced root `docker-compose.yml` with:

| Service | Image/build | Purpose | Required in P1 |
| --- | --- | --- | --- |
| `postgres` | `postgres:16` | Main database, Flyway migrations, tenant data | Yes |
| `redis` | `redis:7` | Captcha TTL, short cache, job progress, locks | Yes |
| `backend` | `backend/` Dockerfile | Spring Boot API | Yes |
| `nginx` | `nginx:alpine` | Optional same-origin frontend/API proxy or private package front door | Optional |

Do not add unrelated services in P1. Object storage, mail/SMS, monitoring, backups, and private delivery variants can wait for later phases unless a concrete P1 smoke test needs them.

Compatibility note:

- `docker-compose.yml` is the canonical P1 Compose file.
- `docker-compose.dev.yml` is a thin compatibility entrypoint for older P1 prompts; it includes `docker-compose.yml` so service definitions do not drift.

## 5. Local Development Port Table

| Port | Service | URL / endpoint | Notes |
| ---: | --- | --- | --- |
| 8000 | Current static frontend | `http://127.0.0.1:8000/Dashboard.html` | Canonical P0 static server port. |
| 4174 | Static frontend fallback | `http://127.0.0.1:4174/Dashboard.html` | Use only if 8000 is occupied or for existing preview compatibility. |
| 8080 | Spring Boot backend | `http://127.0.0.1:8080/api/v1` | P1 app API base. |
| 8080 | OpenAPI / actuator | `/v3/api-docs`, `/swagger-ui/index.html`, `/actuator/health` | P1 should expose health and OpenAPI. |
| 5432 | PostgreSQL 16 | `localhost:5432` | Local dev DB. |
| 6379 | Redis 7 | `localhost:6379` | Local dev Redis. |
| 8088 | Optional nginx | `http://127.0.0.1:8088` | Optional same-origin proxy; maps `/api/v1` to backend and `/` to frontend. |
| 8080 | Schedule progress | `/ws/schedule/{runId}` or future SSE path | Same backend port; transport finalized in implementation. |

## 6. Environment Variables

Root `.env.example` is the P0 baseline. Copy it to `.env` for local development:

```powershell
Copy-Item .env.example .env
```

Rules:

- `.env.example` must contain no real passwords, keys, private URLs, or organization data.
- Real LLM keys stay only in local `.env`, secret manager, or deployment secrets.
- Frontend must not read `JKXT_LLM_API_KEY` or any secret directly.
- `API_PUBLIC_BASE` is `/api/v1`.
- P1 backend reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`, and `SPRING_DATA_REDIS_PORT`.

## 7. First Frontend Files To Connect To API

First API integration phase should touch only:

| File | P4 responsibility | P0 status |
| --- | --- | --- |
| `src/api.js` | Create browser API adapter with `/api/v1`, cookie/session support, error envelope, upload, progress placeholders | Not created in P0 |
| `src/pages/login.jsx` | Replace demo login handler with `/auth/login`, while preserving visual layout | Not changed in P0 |
| `src/app.jsx` | Bootstrap identity from `/auth/me`, restore role/tenant/permissions/onboarded, preserve page shell | Not changed in P0 |
| `Dashboard.html` | Load `src/api.js` after shared primitives and before pages when P4 starts | Not changed in P0 |

Keep `src/pages/quick*.jsx`, `quick-engine.jsx`, `edit.jsx`, `super.jsx`, and `topbar.jsx` unchanged in P0.

## 8. P0 Smoke Test Checklist

Run these checks after P0:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"
Get-ChildItem -Path docs,backend -Recurse -Depth 2 | Select-Object FullName
Test-Path docs\DEV-RUNBOOK.md
Test-Path .env.example
Test-Path backend\README.md
Select-String -Path docs\DEV-RUNBOOK.md -Pattern "Port","Environment","/api/v1","backend/","Smoke","P1"
```

Optional current-frontend smoke:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"
python -m http.server 8000
```

Then in a browser:

1. Open `http://127.0.0.1:8000/Dashboard.html`.
2. Confirm login screen renders.
3. Use demo login and confirm dashboard loads.
4. Open one-key scheduling and confirm the four-step UI renders.
5. Confirm no backend request is expected in P0.

If using PowerShell only:

```powershell
$job = Start-Job -ScriptBlock { Set-Location "D:\VB\JKXT\JKXT V2.0"; python -m http.server 8000 }
Start-Sleep -Seconds 2
(Invoke-WebRequest http://127.0.0.1:8000/Dashboard.html).StatusCode
Stop-Job $job
Remove-Job $job
```

Expected status code: `200`.

## 9. P1 Command Checklist

P1 goal: create the minimal Spring Boot + Docker Compose backend skeleton with health/version endpoints.

Suggested PowerShell sequence:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"

# Confirm P0 baseline.
Test-Path docs\DEV-RUNBOOK.md
Test-Path .env.example
Test-Path backend\README.md

docker compose up -d postgres redis
cd backend
.\mvnw.cmd test
cd ..
docker compose up -d backend
```

Equivalent compatibility command:

```powershell
docker compose -f docker-compose.dev.yml up -d postgres redis
```

In another PowerShell window:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
Invoke-RestMethod http://127.0.0.1:8080/api/v1/version
Invoke-WebRequest http://127.0.0.1:8080/v3/api-docs
```

P1 acceptance:

- `backend/` contains a runnable Spring Boot 3.3 Java 21 project.
- `docker-compose.yml` starts `postgres`, `redis`, and `backend`.
- `docker-compose.dev.yml` is present as a compatibility include for the same services.
- `/api/v1/health` returns healthy status.
- `/api/v1/version` returns app/version/profile/build metadata.
- OpenAPI is reachable.
- Tests pass through `.\mvnw.cmd test` or the chosen Gradle equivalent.

## 10. Product / Design Guardrails

P0 is setup documentation only. Do not redesign screens.

For future frontend work:

- Preserve the login/dashboard/quick scheduling visual system.
- Add real loading/empty/error/data states rather than replacing page skeletons.
- Keep desktop-first behavior unless the phase explicitly targets responsive production hardening.
- `edit.jsx` true drag reassignment is the only currently approved C-level new interaction.

## 11. Completion Evidence For This P0

This P0 is complete when:

- `docs/DEV-RUNBOOK.md` exists and includes startup, ports, env, smoke, and P1 commands.
- `.env.example` exists and contains no real secrets.
- `backend/README.md` exists and clearly marks the backend directory as a P1 placeholder.
- PowerShell verification passes.
- Review gate reports documentation/setup-only risk.

## 12. Completion Evidence For P1

P1 is complete when:

- `docker compose up -d postgres redis` starts PostgreSQL 16 and Redis.
- `backend\mvnw.cmd test` passes through the Docker-backed Java 21 Maven wrapper.
- `docker compose up -d backend` starts the Spring Boot service.
- `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health` returns `status: UP`.
- `Invoke-RestMethod http://127.0.0.1:8080/api/v1/version` returns `application: jkxt-backend`, `stage: P1`, and `apiBase: /api/v1`.
- `Invoke-WebRequest http://127.0.0.1:8080/actuator/health` returns HTTP 200.
- `Invoke-WebRequest http://127.0.0.1:8080/v3/api-docs` returns HTTP 200.
- Review gate reports the changed backend/setup scope and no unresolved high-risk findings.
