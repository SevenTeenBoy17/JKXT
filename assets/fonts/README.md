# Local UI fonts

Downloaded from the official npm registry for mainland-deployable self-hosting:

- @fontsource-variable/noto-sans-sc 5.3.0 — Noto Sans SC Variable, OFL-1.1, weights 100–900. Product typography uses 400/500/600/700. Original Unicode-range CSS loads WOFF2 chunks only as needed, including uncommon Chinese names. Full distributed glyph coverage is preserved.
- @fontsource-variable/geist 5.3.0 — Geist Variable, OFL-1.1, weights 100–900. Latin/numeric UI with tabular numerals; CJK falls through to Noto Sans SC and system fonts.

Each directory retains upstream LICENSE, metadata.json, wght.css and font binaries. CSS keeps font-display: swap. No remote Google Fonts requests are required. No blanket preloads of Chinese chunks: only visible glyph ranges are downloaded. Unavailable fonts fall back to PingFang SC / Microsoft YaHei UI / system-ui.

Source packages and versioned tarballs are retained under artifacts/frontend-polish-20260907/packages for audit. Existing login display font declarations fall back locally when unavailable; the login illustration assets are unchanged.
