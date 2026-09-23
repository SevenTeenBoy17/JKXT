# P11 Production Hardening Verification

This runbook records the repeatable verification gates for the P11 hardening phase.

## Backend Tests

```powershell
cd D:\VB\JKXT\JKXT V2.0\backend
.\mvnw.cmd test
```

Expected: all unit/integration tests pass, including the P11 observability checks.

## Testcontainers Gate

Run only when Docker socket access is available to the Maven container. The script sets the Docker Desktop socket/API environment expected by Testcontainers 2.x on this Windows/Docker Desktop setup:

```powershell
cd D:\VB\JKXT\JKXT V2.0
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\p11\run-testcontainers.ps1
```

Expected: `P11TestcontainersInfrastructureTest` starts real PostgreSQL 16 and Redis containers, applies Flyway migrations, verifies seeded organizations, and receives Redis `PONG`.

## Playwright E2E Gate

Requires the private stack from `docker-compose.private.yml` to be running.
The script uses the bundled pnpm Playwright module path so `playwright` and `playwright-core` resolve together in the Codex desktop runtime.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\p11\run-playwright-e2e.ps1
```

Expected coverage: login, import/sample upload, schedule job, edit reassignment, publish, history report, super tenants, and super security. Screenshots are written to `screenshots/p11-e2e-*.png`.

## k6 Performance Gate

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\p11\run-k6.ps1
```

Expected coverage: health, login, master-data lists, history/statistics, Quick sample import, schedule job, assignments, and fairness.

## Security Scan Gate

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\p11\security-scan.ps1
```

Expected checks:

- health responses include `X-Trace-Id`
- unauthenticated business access returns `AUTH_UNAUTHENTICATED`
- invalid captcha is rejected
- valid login succeeds
- private tenant overreach is blocked with HTTP 403
- unsupported upload kind is rejected

## Backup / Restore Drill

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-private.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\restore-drill-private.ps1 -DropScratchAfter
```

Expected: a `/backups/jkxt-private-*.sql` plus `.sha256` exists, scratch restore counts `organization`, `app_user`, `exam_task`, and `schedule_assignment`, then drops the scratch database.
