# JKXT delivery remediation — G0/G1 execution contract

Date: 2026-09-23. This is an execution contract, not a release approval.

## Source and rollback

- Audited upstream: `codex/source-current-20260922`, `f206bcf6dff291d346cf5c3cf5a68bfce938935e` (remote rechecked).
- Candidate: `D:/VB/JKXT-release-g0-g1`, branch `codex/release-g0-g1-20260923`, isolated Git worktree of that commit.
- Original: `D:/VB/JKXT`, `feat/fullstack-wiring`, `cc84611f8631107eaf79a1d53fad91ed66f3b024`, 75 tracked modifications preserved. All 665 publication-manifest file hashes still match the original. Additional untracked historical material is not automatically current source.
- Rollback: stop only this round's explicitly named test resources and abandon/revert only candidate-owned commits; never reset the original workspace or remove its files. No WIP branch merge. No push/deployment authorized by this execution contract.
- No filesystem AGENTS.md found in checked ancestors or candidate source. User-supplied rules still apply. Supervisor core is globally SUSPENDED; installed skill absent and typed planning tools unavailable. No formal Supervisor acceptance is asserted.

## Atomic criteria

| ID | Requirement | Required evidence |
|---|---|---|
| G0-SOURCE | Actual root, branch, baseline and dirty state; preserve user work | Git identity + hash comparison + inventory |
| G0-INVENTORY | Owned source/config/migrations/scripts/docs, explicit reading depth | Machine-readable per-file inventory and reading log |
| G0-TRACE | UI → request → registered API → authorization → service → storage → artifact → test | Static trace plus separately captured Spring runtime mappings; no conflation |
| G0-PACKAGE | Cover supplied 84 scenarios and additional discovered seams | Original execution package with hash and scenario-level evidence; MISSING until located |
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

## Evidence and limits

Evidence is collected fresh under `docs/release/evidence/`; stdout logs and JUnit results are attributable to one invocation. Counts from static annotations are only discovery estimates; parameterized cases use actual JUnit counts. Compilation with skipped tests is never business acceptance. Numeric quality ratings must not hide hard blockers. Unavailable core tools and audit-only agents do not become formal third-party approvals.
