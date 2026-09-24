# Actual G1 environment check — 2026-09-23

| Dependency | Observed condition | Consequence |
|---|---|---|
| Default Java | PATH points to Oracle Java 8 shim | Do not use default Java for this Java 21 project |
| Selected Java | Temurin 21.0.12.1+1, actual `java -version` exit 0 | Native isolated unit compilation/execution available |
| Maven | Native 3.9.9 under `D:/VB/JKXT-tools/login-entry-20260920/apache-maven-3.9.9`; actual test commands used it | `mvnw.cmd` separately depends on Docker and is not proof of readiness |
| Node | v24.11.1 | Existing Babel syntax check and Node release-tool tests executed |
| Docker client / Compose | Client 29.1.3 / Compose v2.40.3-desktop.1 | CLI/config parsing only; does not imply running containers |
| Docker Engine | Missing `dockerDesktopLinuxEngine` named pipe; Desktop launch failed at stale `dockerInference` reparse socket | BLOCKED; no fresh test Postgres/Redis created |
| PostgreSQL target | `postgres:16-alpine`, fresh named test database and tmpfs in new compose project | NOT_STARTED; no original database contacted |
| Redis target | `redis:7-alpine`, fresh process/tmpfs, nonstandard loopback port | NOT_STARTED; no original Redis/cookies reused |
| Testcontainers | Existing two opt-in test classes require `-Djkxt.testcontainers.enabled=true` and fresh containers | NOT_RUN. Do not count their default skips as acceptance |
| Spring mapping probe | Actual attempted startup rejected by existing test isolation initializer | 1 error, BLOCKED. Static 188 routes are not runtime evidence |
| Supervisor | Explicitly suspended by current user instructions | Do not invoke or require it; ordinary project test gates remain |

## Reproducible isolated start after environment unblock

Run only from `D:/VB/JKXT-release-g0-g1`:

```powershell
pwsh -NoProfile -File scripts/release/run-isolated.ps1 `
  -JavaHome 'D:\VB\JKXT-tools\login-entry-20260920\jdk-21.0.12.1+1' `
  -Maven 'D:\VB\JKXT-tools\login-entry-20260920\apache-maven-3.9.9\bin\mvn.cmd'
```

Each run uses a new name/password, refuses existing named containers/occupied ports, preserves the guard, enables the two container tests, runs all tests without `skipTests`, and captures mappings only if Spring really starts. It does not read `.env.private`, import business dumps, reuse browser sessions, or mount business volumes. Historical V28 migration tests create a separate container; do not point them to this V30 database.

Tests run serially because some fixtures intentionally clear/update shared *test* tables. Retain the named test resources for diagnosis until their evidence is captured; then use the recorded cleanup command for that exact project. Its ephemeral tmpfs data will be lost when stopped; no user/business data should ever enter it.

Environment prerequisites alone do not prove migration correctness, tested rollback, DB assertions, export contents or frontend usability. Those remain separate phase requirements.

## Fresh attempt after supplied documents — 2026-09-24

`run-isolated.ps1 -Run release_g1_20260924_attachments` used the same explicit native Java/Maven paths above. Docker preflight returned 1 (engine named pipe missing); the isolation runner returned 2 and saved `evidence/release_g1_20260924_attachments/environment.json` as BLOCKED with `businessDatabaseAccessed:false`. No PostgreSQL/Redis containers or business tests were started. This is a fresh environment failure, not the reuse of yesterday's report. Targeted Docker runtime-file repair remains pending explicit approval; no volume reset or deletion was performed.
