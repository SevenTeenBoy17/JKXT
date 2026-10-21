# SheetJS browser runtime

- Version: **0.20.3** (vendored unchanged; no network/CDN needed by the running app).
- Downloaded from the publisher on 2026-10-08: https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js
- SHA-256: `cc015130aa8521e7f088f88898eba949ccdcbfb38df0bd129b44b7273c3a6f41` (locally recorded downloaded-content hash, not a publisher signature).
- License: `SHEETJS-LICENSE.txt`, from https://cdn.sheetjs.com/xlsx-0.20.3/package/LICENSE (Apache-2.0).
- Replaces 0.18.5, which was used by the actual uploaded-workbook parser, not only by export code.
- Publisher advisories: https://cdn.sheetjs.com/advisories/CVE-2023-30533 (fixed in 0.19.3) and https://cdn.sheetjs.com/advisories/CVE-2024-22363 (fixed in 0.20.2).
- Official browser integration: https://docs.sheetjs.com/docs/getting-started/installation/standalone/
- Regression: `node --test scripts/xlsx-vendor.test.cjs` invokes the application's actual parseWorkbook/validateSlot with 120 synthetic Chinese teacher rows in XLSX and XLS, preserving all cells and leading-zero identifiers. This is library/import-parser compatibility and a version/hash contract, not an exploit reproduction or a backend/approval E2E test.
- Existing spreadsheet content/export tests must also be rerun after replacement. The old 0.18.5 asset is retained only for historical artifact reproduction and rollback. No application/test entry references it; the production builder explicitly excludes it. The existing source-evidence recorder cannot currently snapshot deleted tracked files, so removal is deferred rather than bypassing that recorder.
