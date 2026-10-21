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

Free Render hosting sleeps and has no durable local disk. Render's free PostgreSQL expires; the current split-host candidate instead uses a dedicated Neon PostgreSQL database in Singapore. Neither choice removes the need for budget, durability, backup and business acceptance. This branch is not approved for dependable commercial school operation.

`release-manifest.json` records the upstream source export files. Its `single-host` route describes the original packaging tool, not this Render topology. The original Caddy/Compose deployment files are retained for source completeness; do not mix their acceptance evidence with Render results.

## Netlify frontend / API entrance

The added [Netlify adapter](deploy/netlify/README.md) publishes only the existing production `dist` build, and proxies `/api/v1` to the existing Java application on Render. The Java backend is not a Netlify Function and has not been rewritten or mocked. PostgreSQL is Neon Singapore; session storage is dedicated Render Key Value Singapore.

Set an explicitly verified `JKXT_API_ORIGIN` at build time, and set Render `APP_BASE_URL` to the Netlify HTTPS origin. Never put database credentials in Netlify frontend files. This is an app-only export: Netlify's base directory is the repository root, not `JKXT V2.0`.

Local adapter/build/portal checks: 29 tests passed, zero failures or skipped tests on 2026-10-21. A live draft exposed canonical `/dashboard` HTML caching; the minimal header fix and a new regression test passed, and a second draft confirmed `no-store` on both query login entries. These checks do not prove backend CDN proxying, Cookie/CSRF behavior, cloud login, solver capacity or production acceptance. Cloud delivery remains incomplete until those separate checks pass.

`release-manifest.json` remains the immutable original 646-file export inventory from commit `15992e4`. This update adds four Netlify files and revises `.gitignore` and this README; its Git commit identifies the updated snapshot. It does not overwrite the development checkout or merge any historical login-isolation branch.
