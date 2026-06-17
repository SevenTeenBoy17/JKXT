# JKXT Backend

Status: P1 runnable backend skeleton.

This directory contains the approved Java 21 + Spring Boot 3.3 modular-monolith backend base for JKXT V2.0. P1 deliberately exposes only health, version, actuator, and OpenAPI endpoints. Business tables, auth endpoints, tenant bootstrap, scheduling logic, and frontend integration belong to later phases.

Why `backend/`:

- `docs/10-CODEX-FULLSTACK-IMPLEMENTATION-PLAYBOOK.md` P1 names `backend/` as the Spring Boot project root.
- `docs/08-BACKEND-DEV-SPEC.md` defines one modular monolith with package-by-feature boundaries.
- `services/api/` is not needed until a later explicit service extraction decision.

Implemented P1 responsibilities:

- Maven Spring Boot 3.3 project.
- Java 21 configuration.
- Dependencies for Web, Validation, Security, Data JPA, PostgreSQL, Flyway, Redis, Actuator, WebSocket/STOMP, OpenAPI, and tests.
- `GET /api/v1/health`.
- `GET /api/v1/version`.
- Minimal Flyway baseline marker migration.
- Dockerfile and root `docker-compose.yml` service integration for `postgres`, `redis`, and `backend`.

Local toolchain note:

- This machine currently has Java 8 and no local Maven/JDK 21.
- `mvnw.cmd` and `mvnw` run Maven through Docker image `maven:3.9.9-eclipse-temurin-21`.
- Docker Desktop must be running before using the wrapper.

Common commands:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"
docker compose up -d postgres redis
cd backend
.\mvnw.cmd test
```

Run the backend through Compose:

```powershell
cd "D:\VB\JKXT\JKXT V2.0"
docker compose up -d backend
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
Invoke-RestMethod http://127.0.0.1:8080/api/v1/version
Invoke-WebRequest http://127.0.0.1:8080/v3/api-docs
Invoke-WebRequest http://127.0.0.1:8080/swagger-ui/index.html
```

Planned package map for later phases:

```text
com.jkxt
  common
  security
  tenant
  org
  masterdata
  importer
  scheduling
  ai
  tasks
  stats
  platform
  notify
  audit
```

Do not place real LLM keys, long-lived browser tokens, tenant private data, or production secrets in frontend files or committed backend config.
