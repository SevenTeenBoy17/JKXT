# JKXT: Render free verification snapshot — NOT production approved

This isolated branch packages the existing Java/Spring Boot application and its compiled-frontend build inputs. It is not a replacement demonstration application. It must not be merged over the development checkout or treated as an approved commercial release.

- Source checkout: `JKXT-release-20261008`, application `JKXT V2.0`.
- Upstream HEAD: `5699c4c842db5b34e6655c2709c69209e4cd21d2`, with preserved candidate changes.
- Source digest at actual image build: `bdfc67ef0c20ef45aa0f9232b60887c80b349ca3e909d83d08b0d44457abdc5d`.
- Locally built image: `sha256:5902bfa531bd4b452fe85b66725082bff64b159759d59df293e407df09a8e81f`.
- Image build succeeded from source. Maven tests were explicitly skipped during packaging; this is not a backend test-pass claim.
- Local startup: Java + nginx, new PostgreSQL 16 and Valkey 8, 512 MiB memory with no extra swap; 12 startup/security assertions passed. Login, real import/solver/export load, cloud deployment and full business acceptance are not covered by those assertions.

## Hosting

Use the repository root as Docker build context and `deploy/render-free/Dockerfile` as Dockerfile. Follow [the free verification adapter instructions](deploy/render-free/README.md). Select Singapore and the **Free** plan for every service. Do not choose paid plans, attach persistent disks, enable external AI/email/SMS, or upload real school data.

Create fresh dedicated PostgreSQL/Key Value resources; inject their credentials only through the hosting environment. No cloud credentials, business databases, uploads, cookies or old build output are included here. Initial operator provisioning remains an explicit, interactive maintenance operation, not a public bootstrap endpoint or default password.

Free hosting sleeps, has no durable local disk, and its database expires. It is unsuitable for dependable commercial school operation. This branch does not authorize production release, promise continuity, or replace backup/restore and deployment acceptance.

`release-manifest.json` records the upstream source export files. Its `single-host` route describes the original packaging tool, not this Render topology. The original Caddy/Compose deployment files are retained for source completeness; do not mix their acceptance evidence with Render results.
