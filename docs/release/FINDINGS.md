# G0/G1 findings and disposition

This is the current-source delivery candidate, not a demo replacement. No main/WIP merge, business DB write, paid AI call, outbound notification, production migration or deployment was performed.

| ID | Severity / evidence | Finding | Disposition |
|---|---|---|---|
| G1-001 | High; valid red/green unit reproduction | Greedy daily-cap decisions incorrectly proved a feasible A/B assignment infeasible | Minimal classifier repair: only static eligibility/availability establish local impossibility. This fixture now returns `failed / DETERMINISTIC_UNKNOWN / DETERMINISTIC_ALLOCATION_EXHAUSTED`; it does NOT yet find B/A. Existing tests unchanged. |
| G1-002 | High release blocker; Docker startup log + CLI exit 1 | Docker Desktop cannot initialize its inference socket; engine unavailable | Dedicated PostgreSQL/Redis creation BLOCKED. Permission requested for targeted, rollback-preserving runtime-file repair; no destructive reset or volume deletion. |
| G1-003 | High evidence blocker | Original execution package with 84 scenarios not located | MISSING; user asked for original file/path. No invented scenario IDs or passing coverage. |
| G1-004 | Medium evidence defect; independently observed | Initial Surefire output override was ineffective | POM exposes `jkxt.test.reportsDirectory` explicitly. Original 121-test log remains valid as a log, but not as a complete immutable XML baseline. New runs have distinct verified XML directories. |
| G1-005 | High gate defect; checker negative tests | A green subset of classes/methods or compilation-only command could pass an early checker revision | Source-derived class+method coverage and skip-command rejection added. Old 39 tests retained; expanded 50-case verifier suite passes. Synthetic verifier fixtures are not product tests. |
| G1-006 | High isolation risk; independent runner review | Workstation Maven options/settings and remote Docker selection could escape intended test environment | Runner clears inherited Maven/JVM/database/provider settings, skips mavenrc, explicitly selects empty Maven settings and local Docker endpoint, rejects repo `.mvn` injection pending review. No bypass of TestDatabaseIsolationGuard. |
| G1-007 | Medium evidence risk; independent runner review | Startup errors or in-run source changes might be misrecorded | Clear stale native exit code; capture startup errors; hash source before and after; source change prevents zero exit acceptance. |

## Preserved unsuccessful attempts

- `counterexample-red`: test compilation error (`roles()` accessor typo), not business reproduction.
- `counterexample-red-assertion` and `counterexample-green`: invalid test fixture initially had CHIEF but omitted required INVIGILATOR eligibility. Both actually failed; the word green in a filename does not change their results.
- `counterexample-valid-red`: corrected eligibility and old predicate, 6 executions / 1 failure. This is the accepted reproduction.
- `counterexample-valid-green`: corrected predicate, 6 executions / 0 failures.
- `runtime-mapping-guard-block`: 1 execution / 1 error; existing isolation guard refused missing explicit DB configuration before datasource creation. Environment BLOCKED, not an assertion pass or usable runtime inventory.
- `coderabbit-review`: CLI failed because base branch was unknown. A second invocation explicitly names the user's current-source base; the first failure remains preserved.

## CodeRabbit review disposition

The actual explicit-base remote review completed with **3 minor issues** across 11 reviewed files. This was a CLI code review, not a Supervisor-signed acceptance or customer release approval. Later runner hardening is independently reviewed/tested but must not be silently attributed to that already captured remote diff.

1. Missing Docker executable should yield a saved BLOCKED environment result: implemented in isolated runner.
2. Replace hardcoded integer V30 assertion with generic Flyway pending-state check: **deferred intentionally**. G0/G1 is pinned to the audited V1–V30 schema; accepting a changed migration set without updating the release contract is undesirable here. A future migration phase must add explicit current/pending/failed/checksum assertions and review its version contract. Existing exact V28 historical test is untouched.
3. Include fixed existing-duty intervals/daily load when proving static infeasibility: **deferred to G4 with new repros**. Current minimal fix is conservative: it may return UNKNOWN for a genuinely infeasible baseline, but it no longer claims a proof from mutable greedy loads. Introducing stronger proofs requires separate negative and cross-plan tests; no claim of exhaustive search is made.

## Follow-up risk queue (static facts, not falsely labelled reproduced exploits)

See `inventory/review-findings.md` and `TEST-INTEGRITY-REVIEW.md`: CSRF disabled with Cookie sessions, process-local async recovery, new/legacy publication parity, old CP-SAT lacks chief/daily-cap input parity and conflates MODEL_INVALID with INFEASIBLE, real AI/notification prerequisites, backup registration vs actual restore, browser production build/E2E. These belong to G2–G8 and remain unaccepted.
