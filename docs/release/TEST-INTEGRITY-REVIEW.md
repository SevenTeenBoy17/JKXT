# G0/G1 independent test-integrity audit

## Scope and evidence boundary

- Reviewed baseline: `f206bcf6dff291d346cf5c3cf5a68bfce938935e` from `codex/source-current-20260922`. Actual isolated candidate branch is `codex/release-g0-g1-20260923` in `D:\VB\JKXT-release-g0-g1`; HEAD still equals that baseline at review time.
- Review type: independent, source-based audit. This document is **not** a CodeRabbit/Supervisor approval and does not claim tests passed. The root worker owns runtime commands, databases, Maven output and final report aggregation.
- Reviewer made no production/existing-test/config edits, ran no Maven, Docker, database, paid AI or notification calls, and used no business cookies. Initially its only persistent candidate-worktree write was this document. A later bounded assignment additionally authorized the new `scripts/release/record-command.test.mjs`, which tests only disposable synthetic recorder fixtures; checker/recorder self-tests allocate and clean their own guarded temporary directories.
- The findings below distinguish source proof, environment prerequisites and **NOT_RUN by this reviewer**. Counts must be replaced/supplemented with fresh Surefire XML before any test-pass claim.

## Discovered test surface

At the audited baseline `backend/src/test/java` contains 72 Java files. 71 contain test methods; `TestDatabaseIsolationGuard.java` is an initializer helper despite its `Test*` filename. Static annotation counts are 248 ordinary `@Test` methods and 7 parameterized methods; parameterized invocations mean these are **not runtime execution counts**.

- 46 classes use `@SpringBootTest`; all 46 declare `@ActiveProfiles("test")`.
- POM has no local Surefire include/exclude or skip configuration. It inherits Spring Boot 3.3.13 Maven test configuration; default naming patterns discover `*Test.java` classes. Resolve effective plugin version from the actual Maven invocation, not inference.
- Two classes are guarded by `@EnabledIfSystemProperty(named="jkxt.testcontainers.enabled", matches="true")`: `P11TestcontainersInfrastructureTest` and `JointPlanningMigrationIntegrationTest`. Leaving the flag absent skips critical database infrastructure/migration tests. Such a run is not the full release baseline.
- No explicit `@Disabled`, JUnit assumptions, repeated-test or dynamic-test annotations were found in this baseline's test sources. Do not confuse method names containing `Disabled` with skipped tests.
- Existing `mvnw` / `mvnw.cmd` invoke `docker run` with the Maven/JDK 21 image. They do **not** forward the five required test environment variables, attach a test network, or mount the Docker socket for nested Testcontainers. Their presence is not proof that running them will execute integration tests safely or successfully.

## Isolation requirements before the first test connection

`src/test/resources/application-test.yml` registers `com.jkxt.security.TestDatabaseIsolationGuard` as a pre-refresh initializer. Preserve it and its existing tests unchanged.

The main Spring suite requires explicit values for all five properties:

1. `JKXT_TEST_DATASOURCE_URL`
2. `JKXT_TEST_DATASOURCE_USERNAME`
3. `JKXT_TEST_DATASOURCE_PASSWORD`
4. `JKXT_TEST_REDIS_HOST`
5. `JKXT_TEST_REDIS_PORT`

The guard requires PostgreSQL database name `jkxt_test_<lowercase_run_id>` with no URL query, userinfo or fragment. Endpoints must be dedicated hosts matching `jkxt-test-*`, or loopback mapped ports >= 1024 and different from PostgreSQL 5432 / Redis 6379. Matching URL/user/password is checked against the resolved Spring datasource; alternate Hikari/Flyway URLs and Redis URL/cluster/sentinel overrides fail closed.

The endpoint-name check alone does not prove physical isolation. The runner must establish fresh resources, inspect their project/container identity and DB name, avoid existing volumes and business networks, and retain their startup identity in its evidence.

### Separate migration infrastructure

`JointPlanningMigrationIntegrationTest` is **not** a Spring context and therefore does not use this initializer. It normally starts its own disposable PostgreSQL container and deliberately migrates to **V28**. That exact historical oracle must stay at V28, while the main Spring suite migrates its different fresh database to HEAD V30.

Its optional `jkxt.test.postgres.url`, `.username` and `.password` system properties bypass the test container and connect directly through `DriverManager`/Flyway without endpoint validation. Never supply the main test DB or any existing DB to those properties. Clear inherited injection in `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and Maven configuration, or execute with a deliberately clean process environment. Let the test allocate its own container.

`P11TestcontainersInfrastructureTest` starts separate PostgreSQL and Redis containers itself. Enable both classes with `-Djkxt.testcontainers.enabled=true`; require a usable Docker API and images. If the API/images are unavailable, report **BLOCKED** for these classes, not PASS and not a hidden skip.

### External and filesystem boundaries

- Clear inherited `JKXT_LLM_*` credentials/routes and external notification settings. Set runtime AI and external SMS/email off in the runner environment; do not override the intentional class-level mock/acceptance properties.
- `AiPolicyApiIntegrationTest` explicitly uses the test-only mock fallback; `AiPolicyAcceptanceAuditIntegrationTest` replaces `LlmClient` with `@MockBean`. These test policy contracts and audit persistence, **not real AI-provider availability**.
- `OpenAiCompatibleLlmClientTest` uses a loopback `HttpServer` with synthetic credentials. The `http://192.0.2.1/v1` case is a rejection-before-network test. Never replace it with an actual billable endpoint to obtain a pass.
- The current `LoggingEmailSender` and `LoggingSmsSender` only log; `NotificationDeliveryService` marks unsupported external channels `CHANNEL_NOT_CONFIGURED`. Their green tests do not prove real email/SMS delivery.
- `AdminPlatformService` scans a fixed `/var/backups/jkxt` directory and can register discovered dump files in the test DB. Do not mount business backup volumes; keep that directory absent/empty or use isolated test artifacts only.
- No direct file-writing or shell process-launch calls were found in scanned test sources. Uploaded Excel tests construct in-memory files; DB insertion/update/deletion is expected in integration suites and is permitted only in the fresh test DB.

### Execution policy

Run the full suite serially against one fresh isolated main DB/Redis, with no other Maven test runner sharing these resources. In particular, `AiPolicyApiIntegrationTest` deletes all AI call log rows in `@BeforeEach`, and multiple classes mutate the common seeded test organization. Parallel test execution would risk false failures or misleading results.

Do not run any full-context class before the guard and fresh infrastructure prerequisites hold. Plain unit/context-runner tests can be selected as a separately labelled subset if Docker is blocked. Do not label that subset “all tests”. A `-DskipTests` package result is compilation only.

## High-confidence current counterexample finding

**Severity: High. Source-proven control-flow issue; runtime reproduction delegated to root.**

There is no `QuickScheduleSolver` class at this baseline. The relevant greedy path is `examops/service/DeterministicInvigilatorAllocator.java`; the legacy solver is separately `scheduling/solver/CpSatScheduleSolver.java`.

In the greedy allocator, `withinDailyLimit` is computed from loads containing earlier assignments made during the same allocation. If no candidate is selected, `intrinsicallyUnavailable` is computed from that already-pruned list. The resulting `infeasible / DETERMINISTIC_INFEASIBLE` result can therefore be based on the algorithm's own earlier greedy choice, not a proof that all assignments are impossible.

Exact user counterexample:

| Teacher | Qualified roles | Daily maximum | Morning 08:00–10:00 | Evening 16:00–18:00 |
|---|---|---:|---|---|
| A (first by number) | CHIEF + INVIGILATOR | 1 | Available | Available |
| B | CHIEF + INVIGILATOR | 1 | Available | Unavailable |

Both slots require exactly one chief, with no other duties or blocking leave. Greedy selects A for morning; A is pruned by the daily limit for evening; B fails the evening window; the allocator calls that intrinsic shortage and returns infeasible. The independent feasible assignment is morning B / evening A.

Existing `reportsGreedyExhaustionAsUnknownRatherThanClaimingGlobalInfeasibility` covers overlapping slots under default daily limit 4, not this daily-limit corner. Existing daily-limit tests cover one-teacher shortage and cross-plan load, so they do not expose the false proof.

### Minimal regression test and independent oracle

Add a new test (do not weaken any existing test) in `DeterministicInvigilatorAllocatorTest`:

1. Build two disjoint slots on the same date and the A/B availability/eligibility above.
2. Encode the expected feasible witness explicitly as B/morning and A/evening. Independently assert one chief per slot, every teacher at most once that day, and window inclusion. Do not ask the production allocator/eligibility helper to validate its own answer.
3. Execute the actual allocator. A truthful result may be either the correct full assignment or `failed / DETERMINISTIC_UNKNOWN` with a search-incomplete explanation. It must not return `infeasible`, `DETERMINISTIC_INFEASIBLE` or claim proven capacity shortage.
4. Include permutation invariance (reverse input roster) and the existing genuinely impossible one-teacher case to distinguish incomplete search from proven shortage. Never change the existing daily-limit assertion merely to accommodate a repair.

For an implementation that only corrects the diagnostic, retain the “search incomplete” limitation explicitly. Do not describe such a change as solving all feasible allocation problems.

### Legacy CP-SAT limitation

`ScheduleFixture.TeacherInput` has no per-teacher daily-limit or chief-qualification fields, and the baseline `CpSatScheduleSolver.solve` does not accept the `TeacherEligibility` map. Thus the user’s full counterexample constraint set cannot currently be expressed through that legacy solver input. Its existing six tests do not establish cross-generation constraint parity. Record this as a later-stage requirement gap; do not incorrectly use a feasible CP-SAT output as proof that daily-limit/chief-role rules were enforced.

## Release-check test-integrity acceptance

Before accepting the new checker, independently prove rejection of: empty/missing XML, zero tests, failed tests, error tests, skipped critical classes, missing expected class, wrong Git SHA, wrong candidate worktree/source identity, and stale or mismatched artifact hashes. Negative fixtures are checker self-tests, not product acceptance results. Persist failed attempts and do not overwrite a baseline failure with a retry's pass.

The guard/helper hash and existing test diff should be checked against baseline. No test deletion, new skip, changed existing expected value, broad mock replacement or threshold relaxation is authorized as a means to get green. A separate fresh regression failure must precede a product fix.

## Review status

- Test suite source/isolation audit: completed within the bounded files above.
- Runtime suite results: **NOT_RUN by reviewer; see root execution records**.
- New checker/diff integrity verdict: **No remaining blocking finding in the bounded reviewed diff**, after the follow-up corrections below. This is an audit-only review, not a signed external acceptance.
- Formal Supervisor acceptance: **MISSING**, not replaced by this audit. The root worker later reported an explicitly base-bound remote CodeRabbit attempt in progress; its result is **PENDING** until independently received and bound by the root, not a completed approval from this reviewer.
- Release verdict: **NOT APPROVED**. The exact false-infeasible counterexample has fresh narrow red/green evidence after repair; fresh all-suite, actual registration, original execution-package and later-stage evidence are still separate requirements.

## Independent review follow-up: evidence and fixture validity

Fresh root-worker evidence inspected read-only:

- `evidence/baseline-unit.command.json` / `.txt`: the command selected 23 non-full-context/non-container classes; log reports 121 tests, 0 failures, 0 errors, 0 skipped, exit 0. The reviewer recomputed the log SHA-256 as `727f59c9e4f523a13b78efe032d59b49dda5c64404f76c67b1e1718bd5528faf`, matching its command record. This is a unit/slice subset, not the full suite.
- Its initial `-Dsurefire.reportsDirectory=...` property did not produce the requested directory: the actual 23 XML files were under `backend/target/surefire-reports`. The root worker was notified before further test evidence was accepted. A subsequent POM change introduces an explicit `jkxt.test.reportsDirectory` property wired to Surefire; it changes output location only, not discovery, assertions or skips.
- `counterexample-red` is a test compilation failure (`roles()` does not exist on `TeacherEligibility`), so it is not a reproduced product failure.
- The first `counterexample-red-assertion` and the run labelled `counterexample-green` each report 6 tests / 1 failure / 0 errors / 0 skips. The label “green” is not evidence of passing.
- Further independent dependency review found the initial new fixture supplied only `CHIEF`. `ExamOperationsHardConstraints.teacherEligible` also always requires `INVIGILATOR`, so those first assertion failures were **invalid-fixture failures**, not valid reproductions of the user's fully qualified A/B counterexample. The root worker was explicitly notified to add both qualifications, rerun against baseline production behavior, then reapply the minimal fix and rerun. The reviewer corrected its earlier preliminary assessment rather than treating the invalid red run as proof.

The checker initially accepted a selected green subset because it did not compare runtime reports with independently discovered classes. That issue was raised and the current checker now derives required test classes from current hashed Java source, verifies the declared list, and rejects missing-class reports. Its negative self-tests and final source identity still require fresh execution/review before any final local approval.

The reviewer independently executed `node --test scripts/release/check-release.test.mjs` from the candidate root after the class-coverage change: **39 tests, 39 passed, 0 failed/cancelled/skipped**, exit 0. At this reviewed point the checker hash was `0123bef43b5b0dca211307de9ddd56421f6a273ac372d79d593966b184f9b253`; its test hash was `0dd2a77ec749e66f7b4cd54788fe4632263c5cbdcb26bd1ba9fb97f8e4791aaa`. This is verifier self-testing, not a JKXT business test pass.

The reviewer then identified a remaining loophole: one green testcase per source class satisfies class coverage even if a second critical method in the same class never ran. Requested the checker author to add a two-method/one-reported-method negative fixture and source-derived method coverage, and to reject test-command skip flags. This finding means the 39 passing self-tests alone do **not** earn checker approval.

`counterexample-valid-red` was subsequently inspected: fixture now includes both `INVIGILATOR` and `CHIEF`, its eligibility precondition passes, and the restored old classification predicate fails the new user-example assertion with **6 tests / 1 failure / 0 errors / 0 skips**, exit 1. This is the valid pre-fix regression evidence. The contemporaneous source retained only a diagnostic-message difference from baseline; the old `withinDailyLimit` classification behavior was restored for that run. Its log hash recorded by the runner is `46261f9b5f5d686ca567c54cd575da8bec04a5698f2d835749341098c91c9104`.

## Final bounded re-review

### Product regression and test integrity

`counterexample-valid-green` is the fresh corrected-fixture, corrected-production run: **6 tests / 0 failures / 0 errors / 0 skipped**, exit 0, from `2026-09-23T15:36:02.8061830Z` to `2026-09-23T15:36:18.0951758Z`. The reviewer independently recomputed its log SHA-256 as `2eef6f5c36cb28a10cedc52859312c87f05740dbc3ba3eaf82e4a0567f0e4363`, matching the runner record, and inspected the six testcase XML entries.

- Production repair changes the static impossibility predicate from the already capacity-pruned teacher list to the qualified teacher list and explains search incompleteness. It does not add a complete search algorithm or claim optimality.
- The new regression permits only an exact B/morning + A/evening success or an empty `DETERMINISTIC_UNKNOWN / DETERMINISTIC_ALLOCATION_EXHAUSTED` failure. It rejects false `infeasible`. The call to the production eligibility helper is an additional fixture-validity precondition, not the feasible-assignment oracle.
- Existing allocator tests and their expected values are unchanged: the test diff adds 48 lines and removes 0. No new skip, removed assertion or replacement of the backend with mocks was introduced.
- The existing database guard remains unchanged; reviewed SHA-256 is `1164a74a9b6e0d09792d1d5918143872bac96d27fd6856a82fa10a6f922805f6`.
- POM changes only expose the per-run Surefire report directory. They do not exclude tests or change skip settings.

The green execution captured source digest `7a8304a737f29020abc9347700b425fa6b7761224df67aa5bced19f68d53fb35`. Later checker changes moved the full-source digest to `7eb94d20edcc6e0b60f744ff072bad57e8215626cd7fabdbcd4fed6c45a74161` at this review. Do not silently rebind the earlier run to the later whole-candidate digest; fix the final candidate snapshot and run the required affected/full checks again for a final gate. This is valid narrow repair evidence, not a current full-candidate pass.

### Release checker retest

The checker author added method-level coverage, normalized actual Surefire parameterized names, removed Java comments/literal decoys before discovery, rejected unsupported dynamic/nested/inherited structures, and rejected compilation-only skip flags. The original 39 self-tests are retained.

The reviewer independently executed `node --test scripts/release/check-release.test.mjs` again from the candidate root: **50 discovered / 50 executed / 50 passed / 0 failed / 0 errors / 0 skipped / 0 cancelled**, exit 0, elapsed 11.856 seconds. Reviewed artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `scripts/release/check-release.mjs` | `7ddc6c108cdc13eceb7a849eaee697cf29647f77885b6c581c776c6d4cf134d1` |
| `scripts/release/check-release.test.mjs` | `785c08f261b468f165396543b8cf087dabfe6b7fd8b41035d44364d0db6ca8ed` |

Read-only source discovery through the current checker reports **72 classes / 257 declared methods**, including the new registration probe. These are source counts, not runtime invocation counts. The class and method omission findings are resolved within this implementation. Parameterized invocation completeness still requires a future independent discovery/invocation manifest, as the contract explicitly states. The checker is a consistency/minimum-evidence gate, not a signed command attestation or a production release gate. Its synthetic positive fixture is never product evidence.

### New integration probe and isolated infrastructure review

`RegisteredApiInventoryTest` uses the existing `test` profile and therefore retains the database isolation initializer. It asserts an actual `jkxt_test_*` database, successful schema V30 with zero failed migrations, Redis `PONG`, and real `RequestMappingHandlerMapping` registrations including auth, health and plan routes. Permission annotation text is explicitly not presented as the entire security policy. Its only newly introduced filesystem write is the chosen runtime-mappings JSON; the runner must keep that output under the current isolated evidence directory and set the run ID, SHA and source digest. Unbound output is rejected by the checker. **The reviewer has not executed this probe; source review is not runtime registration proof.**

`compose.release-test.yml` specifies separate Postgres/Redis services with fresh tmpfs storage, no existing business volumes, and loopback-only mapped ports. Run-specific Compose project identity, password, database suffix and clean process environment remain runner obligations; the file alone does not prove that those resources were actually created or used. The migration test must still allocate its own separate historical-V28 database rather than reuse this V30 database.

### Remaining release limits

No business database, real provider or notification target was touched by this reviewer. Full integration/Testcontainers execution, real mapping output, original 84-scenario package, DB/export content assertions, all required G2+ behavior and production safety gates remain owned by the root plan. Legacy CP-SAT input parity remains a later-stage requirement; its source also maps `MODEL_INVALID` together with `INFEASIBLE`, which should be separately reproduced and classified before repair. None of these open requirements is waived by the 50 checker self-tests or six allocator tests.

## Final follow-up: isolated runner and command recorder

Requested scope: read-only review of `scripts/release/run-isolated.ps1`, `record-command.ps1` and their direct `capture-source.mjs` dependency. The reviewer did not run these scripts, Docker or Maven. The fresh root records were independently read, hashed and their actual XML nodes parsed:

| Run | XML reports | Discovered / executed | Failures / errors / skipped | Exit | Log SHA-256 |
|---|---:|---:|---:|---:|---|
| `final-unit-regression` | 23 | 122 / 122 | 0 / 0 / 0 | 0 | `6ca333154e871d6b54d58fb9a90686599404dafbb18bded8ae0fff9ff830a39c` |
| `runtime-mapping-guard-block` | 1 | 1 / 1 | 0 / 1 / 0 | 1 | `415cd78e1b08f07f03213cea393fffebc1568beb9d031bb419a1058e6725322b` |

The mapping probe error is `TEST_DATABASE_ISOLATION_REQUIRED: Missing explicit JKXT_TEST_DATASOURCE_URL`, before application infrastructure initialization. Its correct stage status is **BLOCKED**, not a runtime-mappings PASS. The 122-test result is a real unit/slice/solver impact regression, not the full integration suite. No current all-suite pass follows from either row.

### Runner strengths observed

- Uses a dedicated run suffix/project/database, random disposable password, nonstandard distinct loopback ports, current Compose file, healthy-start requirement and fresh-project/fresh-port checks. Existing volumes are not attached.
- Clears `JKXT_*`, `SPRING_*`, database/Redis environment and common injected JVM options before assigning test credentials; real provider/SMS settings are disabled. Passwords are passed through environment, not included in the actual recorded Maven argument list or environment metadata.
- Reused run evidence directories and existing log/command IDs are rejected. `capture-source.mjs` independently rejects an already-existing source snapshot, so an orphan snapshot is not silently overwritten even though the recorder's earlier precheck names only log/record files.
- Docker preflight fails before infrastructure creation; source review and the root's reported blocked execution do not imply that a database was created.

### Required hardening raised to the implementer

1. **Inherited Maven injection is not yet completely excluded in the first reviewed runner.** `MAVEN_ARGS`, `MAVEN_DEBUG_OPTS`, `JVM_CONFIG_MAVEN_PROPS` and user Maven startup scripts remain possible inputs. This is verified against the actual installed Maven 3.9.9 `mvn.cmd`: it invokes user `mavenrc_pre` at lines 39–40 and passes those option variables around lines 173–181. In this repository the direct-connection migration test bypasses the Spring guard when `jkxt.test.postgres.url` is set. Clear relevant inherited Maven variables, disable user RC loading, bind/review Maven project/settings configuration and preserve the migration test's own disposable DB.
2. **Recorder launch-failure return code can be stale in the first reviewed version.** The snapshot command has just set `$LASTEXITCODE` to 0; recording switches error handling to `Continue` and then copies `$LASTEXITCODE` after invocation. If a launch fails without setting a native exit code, it can inherit that earlier 0. Clear the code before invocation, capture invocation errors and fail closed for missing/non-numeric native exit status. This is a source-level risk; the already-inspected successful Maven logs contain actual completion and are not reclassified as false passes.
3. **Execution-time source drift needs a post-command check.** The first recorder captures only the pre-command snapshot. Capture and compare after execution, retain both identities and mark any mismatch rather than binding output to unchanged source by assumption.
4. **Docker/Testcontainers host provenance must be local and explicit.** Validate or constrain Docker context/host and account for Testcontainers' user configuration rather than allowing inherited remote `DOCKER_HOST`/`DOCKER_CONTEXT` or `~/.testcontainers.properties` to choose an external engine. Current engine preflight failure does not exercise the creation path.

These points were sent to the root for minimal fixes and synthetic failure-path validation. They remain pending re-review until the amended scripts and results are supplied. No reviewer change to production, test or runner code was made. A first suspicion about orphan-snapshot overwrite was withdrawn after reading the downstream `existsSync` refusal, and is not retained as a defect.

## Runner hardening re-review and independent synthetic checks

The root amended the recorder to clear native exit state, return 127 for missing/invocation-failure exit status, retain `nativeExitCode`, capture a post-command snapshot and HEAD, and return 86 when an otherwise-successful command changed source identity. The source/log/record/after-snapshot names are all now checked before execution. Reviewed recorder SHA-256: `f9ccab36d111b095a126a3d07914312dea282923ff9d74a5f3ce9c7cf6ed739f`.

With the root's explicit additional file ownership, this reviewer created **only** `scripts/release/record-command.test.mjs`. Its tests copy current helpers/checker into independent temporary Git repositories, initialize a single synthetic source-test declaration for source discovery, and spawn noninteractive PowerShell/Node commands. They never call Maven/Docker or touch business data. Temporary cleanup validates the full parent directory and fixed unique prefix first.

First execution found 3 passed / 4 failed due to the **test host**, not recorder behavior: PowerShell `-EncodedCommand '& script.ps1'` normalizes a failing script's process result to 1. The host was corrected to explicitly propagate `exit $LASTEXITCODE`; expected native/recorded codes and all assertions remained intact. That unsuccessful attempt is retained here and is not labelled a product red test.

Fresh independent command `node --test scripts/release/record-command.test.mjs` then returned **7 discovered / 7 executed / 7 passed / 0 failed / 0 errors / 0 skipped / 0 cancelled**, exit 0, elapsed 7.110 seconds. It verifies:

1. Actual native exit 0 with matching pre/post source identity.
2. Actual native exit 7 preserved despite later successful snapshot commands.
3. A harmless PowerShell cmdlet with no native exit cannot reuse a stale 0; it records/returns 127.
4. A harmless invocation error on a nonexistent fixture path records/returns 127. This does not claim an OS-level invalid-executable or permission-denied launch was separately exercised.
5. A successful synthetic command that edits its own fixture source records native 0 but returns 86 and `sourceUnchanged: false`.
6. Duplicate evidence ID is rejected and all original evidence bytes remain identical.
7. An orphan source snapshot similarly prevents reuse without modifying its original bytes.

The test file SHA-256 at this execution is `b0fc5b239e742bc0888523b1b575a791447556a03323703aebc9ab8439625eec`.

The revised isolated runner clears all Maven/JVM/Docker/Testcontainers inherited variables, disables Maven RC files, supplies reviewed empty user/global Maven settings, pins Docker Desktop's Linux context and named pipe and verifies the context endpoint. These are source-reviewed improvements; Docker resource creation remains unexecuted/blocked.

One final Maven binding correction was requested: installed `mvn.cmd` line 80 resets `MAVEN_PROJECTBASEDIR` from `MAVEN_BASEDIR`, so setting the former alone does not pin the root. The final readback confirms `run-isolated.ps1` lines 24–25 now bind both `MAVEN_BASEDIR` and `MAVEN_PROJECTBASEDIR` to the backend. This resolves that source-review finding. No remaining blocking finding was identified in this bounded runner review; actual Docker resource creation/full-suite execution and formal review requirements are still unfulfilled by these synthetic tests.
