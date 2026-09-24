# JKXT delivery remediation — G0/G1 execution contract

Date: 2026-09-23; amended 2026-09-24 after reading both supplied documents completely. This is an execution contract, not a release approval.

## Source and rollback

- Audited upstream: `codex/source-current-20260922`, `f206bcf6dff291d346cf5c3cf5a68bfce938935e` (remote rechecked).
- Candidate: `D:/VB/JKXT-release-g0-g1`, branch `codex/release-g0-g1-20260923`, isolated Git worktree of that commit.
- Original: `D:/VB/JKXT`, `feat/fullstack-wiring`, `cc84611f8631107eaf79a1d53fad91ed66f3b024`, 75 tracked modifications preserved. All 665 publication-manifest file hashes still match the original. Additional untracked historical material is not automatically current source.
- Rollback: stop only this round's explicitly named test resources and abandon/revert only candidate-owned commits; never reset the original workspace or remove its files. No WIP branch merge. No push/deployment authorized by this execution contract.
- No filesystem AGENTS.md found in checked ancestors or candidate source. Current user-supplied rules replace older rules: Supervisor is suspended indefinitely. Do not activate, repair, invoke, finalize or require a Supervisor report. Ordinary project isolation, tests and independent review still apply.

## Atomic criteria

| ID | Requirement | Required evidence |
|---|---|---|
| G0-SOURCE | Actual root, branch, baseline and dirty state; preserve user work | Git identity + hash comparison + inventory |
| G0-INVENTORY | Owned source/config/migrations/scripts/docs, explicit reading depth | Machine-readable per-file inventory and reading log |
| G0-TRACE | UI → request → registered API → authorization → service → storage → artifact → test | Static trace plus separately captured Spring runtime mappings; no conflation |
| G0-PACKAGE | Cover supplied 84 scenarios and additional discovered seams | Both originals now preserved byte-for-byte under inputs/, normalized with full given/action/expected and source lines; input verified, execution statuses initially NOT_RUN |
| G1-ISOLATION | Dedicated PostgreSQL/Redis; retain TestDatabaseIsolationGuard | Guard tests; explicit endpoint/name config; isolation assertions; no business connection |
| G1-BASELINE | Execute applicable existing tests, no false green | Fresh logs/XML, command/exit/time/SHA, discovered/executed/failure/error/skip counts |
| G1-MAPPINGS | Capture Spring's actual handler registrations | Running Spring context against dedicated storage; NOT_RUN/BLOCKED when unavailable |
| G1-GATE | Fail closed on missing/empty/stale/failing/skipped evidence | Executed adversarial checker tests and actual baseline rejection/acceptance |
| G1-COUNTEREXAMPLE | A/B morning/evening chief assignment regression | Independent oracle: morning B, evening A; or explicit search-incomplete, never false infeasible |
| G1-HANDOFF | Honest durable status and independent integrity review | STATUS.md, unresolved defects, exact command evidence, commit and next task |

## Execution order

1. Source verification and independent inventory/test-safety audit.
2. Record prerequisites and existing pure-unit baseline. Docker wrapper is not native Maven; Java 21 and native Maven are explicitly selected if used.
3. Start fresh isolated test infrastructure if available; database `jkxt_test_release_g1_*`, dedicated loopback ports or `jkxt-test-*` service names, no mounts of existing business volumes. Existing test reports are never copied in as results.
4. Existing full applicable suite, including opt-in container tests when infrastructure works; capture live Spring mappings. Environment failures are BLOCKED, tests not run are NOT_RUN, observed assertions remain FAIL.
5. Add diagnostic route inventory and requested regression; reproduce before minimal scoped corrections. No deleted tests, new skips, relaxed assertions, disabled DB guard, or core mocks.
6. Run checker adversarial self-tests, review changes and test integrity, save phase status. Missing execution package or infrastructure blocks release even if local tests pass.

## Subsequent phase queue (not silently claimed complete)

G2 authentication/session/CSRF/production security → G3 master data/Excel/migrations → G4 old/new scheduling constraints, independent result verification, unified publication → G5 async recovery/notifications/real AI → G6 reporting/settings/backup restore → G7 production frontend and real E2E → G8 frozen-candidate independent release review.

Every defect follows reproduce → minimal fix → focused regression → impact regression. Real-school data mutations, paid external AI, outbound email/SMS, production migrations, domain changes and public deployment require separate authorization. Synthetic isolated fixtures must be labelled synthetic; they do not prove real AI or external delivery.

## Supplied acceptance and architecture contract — 2026-09-24

Both complete documents (including preamble, state definitions, scope appendix and nested subsections) are retained, not rewritten as instructions granting extra privileges:

- `inputs/02_后端与全栈验收矩阵.md`: SHA-256 `27289a1ca019087740f2ed7e7c83ea147db6e1726c7d33ff4034858628fe0c5c`, 14 groups / 84 scenarios; baseline f206bcf.
- `inputs/03_架构与优化决策.md`: SHA-256 `227cd4656dbdd90cd4e0aada8f3832f659868e402f35e98dd24088ff18ed87a4`, all 12 sections and subsection details preserved.
- `execution-package.json` stores each full requirement, original line and source hash, plus full architecture section text. The verifier rebuilds it from the originals and rejects altered IDs, weakened expectations, omitted sections or invented scenarios. Provenance consistency is not evidence of execution or independent authenticity.
- `scenario-ledger.json` contains all 84 individual execution slots, initially NOT_RUN, with fields for test IDs, command, environment, candidate SHA/digest, exit, assertions, reports, defects and retests. Historical unit evidence does not silently promote these slots.
- `SCENARIO-COVERAGE-REVIEW-20260924.md` maps every group to existing entry points/tests and missing evidence; `ARCHITECTURE-DELTA-20260924.md` distinguishes current facts, historical fixes and unverified risks in all 12 sections.

| Phase | Matrix minimum | Explicit additional contract from architecture document |
|---|---|---|
| G0/G1 | ENV-05/06, RELEASE-02/05 foundations; inventory for all groups | Keep Java/Spring/PostgreSQL/Redis and current UI; real handler registry + async/downloads; source/data isolation, no prototype/WIP merge |
| G2 | ENV-01–04, AUTH-01–06, PERM-01–06 | Whole login/me/logout/CSRF/upload/download/SSE/multitab protocol, force-password-change and absolute/session-index TTL; never copy WIP backend alone |
| G3 | DATA-01–06, IMPORT-01–06, RELEASE-01 | V30 deletion semantics reproduced in PostgreSQL; only additive migrations; atomic/partial import policy per field; immutable input fingerprints/history |
| G4 | SOLVE-01–06, PUBLISH-01–06 | Unified hard constraints across old/new/manual routes, independent oracle and result validator, honest UNKNOWN vs INFEASIBLE/MODEL_INVALID, total deadline; one effective version and publication checks |
| G5 | ASYNC-01–06, NOTIFY-01–06, AI-01–06 | Durable lease/recovery/cancel/queue; truthful LOGGED/unconfigured states; provider events separate from client receipts and transaction; approved external budget only |
| G6 | REPORT-01–06, OPS-01–06 | Parse actual XLSX/PDF, deterministic money/time; backup temporary+nonempty+checksum+manifest and restore into blank DB; no Docker socket in business container |
| G7 | FRONT-01–06 | Preserve visual direction and window bridges while adding production build; real errors/idempotency/version handling, browser-native SSE protocol, no core-API mocks for acceptance |
| G8 | RELEASE-01–06 plus all prior required results | Fixed candidate/build/image identities, SBOM/capacity/recovery, independent final review and school UAT; production needs separate human approval |

Do not introduce microservices/Kubernetes/full TypeScript as prerequisites. Do not make prototype age/gender preferences hard requirements. Teacher subject warning policy must not be confused with an invalid examination foreign key. Do not relabel logs/provider acceptance as delivered/read, or settings persistence as execution. Optional external capability non-authorization remains BLOCKED/not-delivered, not an unapproved scope exclusion or PASS.

Attachment03's A/B statement “not executed” is historical: valid red/green evidence now exists. Current implementation deliberately returns truthful UNKNOWN for the greedy dead end, not a found B/A solution or exhaustive infeasibility proof. Preserve original wording and annotate the delta separately. Later assertions must not be weakened to obtain a green run.

## Evidence and limits

Evidence is collected fresh under `docs/release/evidence/`; stdout logs and JUnit results are attributable to one invocation. Counts from static annotations are only discovery estimates; parameterized cases use actual JUnit counts. Compilation with skipped tests is never business acceptance. Numeric quality ratings must not hide hard blockers. Unavailable core tools and audit-only agents do not become formal third-party approvals.
