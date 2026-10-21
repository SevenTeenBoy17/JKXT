# Netlify frontend and API entrance

This adapter serves the existing production frontend from Netlify and proxies `/api/v1` to the existing Java service on Render. Java, native scheduling, Valkey and the Neon database do not run on Netlify. It adds no Functions, Edge Functions, database extension or paid service.

## Build configuration

For the full repository, set the Netlify **Base directory** to `JKXT V2.0`; Netlify finds `netlify.toml` there. For an app-only exported repository, use the repository root as the base. Do not set the full repository root as the publish directory. The configuration publishes only `dist`.

Set `JKXT_API_ORIGIN` in the Netlify site's build environment to the actual Render service's public HTTPS origin. It must be exactly `https://` plus one real service hostname under `onrender.com`, without a trailing slash, credentials, port, path, query or fragment. Placeholder names are rejected. This is public routing information. Never put a Neon connection string, database password, Valkey credential or operator password in Netlify frontend variables or files.

The configured command is:

```text
npm run build && node scripts/deploy/prepare-netlify.mjs
```

It uses the existing lockfile/build, validates the frontend manifest and writes only `dist/_redirects` and `dist/_headers`. Missing or invalid origin, missing/inconsistent build, private filenames, unexpected top-level output and links fail before either deployment file is written. The adapter does not read `.env`, upload anything or delete a directory. A separate rebuild replaces the existing generated `dist` through the pre-existing build script.

For a manual deployment, run the build and adapter successfully before selecting `dist` for upload. Confirm the account is on the authorized Free plan and no paid option is selected. `.netlify/` must be ignored before linking a site; it contains local site binding state. Publishing or pushing is a separate operation; adding this adapter does not perform it. Keep Netlify's secret scanning enabled.

## Existing entry and proxy behavior

| URL | Behavior |
| --- | --- |
| `/` and `/Dashboard.html` | Existing school login |
| `/?portal=super` and `/Dashboard.html?portal=super` | Existing platform login |
| `/dashboard` and `/dashboard?portal=super` | CDN-normalized equivalents, with the same portal query |
| `/Dashboard.html?login=super` or `?entry=super` | Existing legacy platform query aliases |
| `/api/v1` and `/api/v1/*` | Forced HTTPS proxy to the same path on Render |
| Missing assets, unknown pages, `/api/v2/*` | No HTML success fallback; normal missing-path response |

Portal selection remains query-based; the application does not recognize `/school/login` or `/platform/login` as page entries. The adapter does not invent these paths. Query parameters on the root rewrite remain in the browser address and on API proxies are forwarded by Netlify. Existing `jkxt:apiBase` local storage development overrides still override the default API base; use a clean browser profile for same-origin acceptance.

The proxy preserves the API prefix. `200!` declares a forced proxy rewrite, not an instruction to replace every upstream response status with 200. An unknown `/api/v1/*` must be rejected by the actual backend and must never return the Dashboard HTML. Local rule tests do not prove this behavior at the deployed CDN.

Static headers prevent MIME sniffing and framing while leaving legacy inline styles, inline handlers and scripts executable. Only manifest-verified hashed files under `/compiled/` receive immutable caching; unhashed assets receive short caching and HTML is not stored. Netlify's static header rules do **not** apply to proxy responses; Render must provide API security/cache headers. Post-processing is disabled to preserve build bytes, but the CDN can still normalize `/Dashboard.html` and `/Dashboard` to `/dashboard` while retaining the query. Cache headers therefore cover the original HTML path, `/dashboard` and `/dashboard/`; no redirect is added to fight CDN normalization.

The first live draft on 2026-10-21 exposed this header gap: `GET /Dashboard.html?portal=super` returned `301 Location: /dashboard?portal=super`, then `200 Cache-Control: public,max-age=0,must-revalidate`, while the root already returned `no-store`. The added canonical-path regression first failed with 13/14 passing (`/dashboard` had no cache rule). This failure is retained; local success after the fix requires a new draft and a separate live HTTP recheck before declaring the deployed cache policy corrected.

## Verification and known limits

```text
node --test scripts/netlify-deploy.test.mjs
npm run build
node --test scripts/frontend-build.test.mjs
node scripts/deploy/prepare-netlify.mjs
```

The final command requires the real `JKXT_API_ORIGIN` environment variable; it never substitutes a sample backend automatically. Adapter tests use temporary synthetic builds and do not contact Render or Neon. Filename checks and controlled environment interpolation are safeguards, not a complete secret audit of authored assets.

Before claiming the topology works, verify actual HTTPS school/platform login, both session cookies, CSRF failure/success, logout isolation, unknown API status/body, exports and cold-start behavior through the Netlify origin. Netlify documents a **26-second proxy timeout**. Long solver requests and SSE streams require actual testing, including interruption/reconnect behavior; this adapter does not establish their compatibility. Free quotas and Render sleeping behavior are deployment limits, not removed by static routing. Free-only authorization must be checked before any cloud change.

Official references checked for this adapter: [rewrites and proxy limits](https://docs.netlify.com/manage/routing/redirects/rewrites-proxies/), [query and redirect options](https://docs.netlify.com/manage/routing/redirects/redirect-options/), [monorepo base/publish directories](https://docs.netlify.com/build/configure-builds/monorepos/), and [static header limitations](https://docs.netlify.com/manage/routing/headers/).
