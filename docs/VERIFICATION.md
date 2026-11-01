# P2 scans, scheduling and permissions verification — 2026-11-01

Build: **1.3.1-dev**, branch `fix/p2-scans-scheduling-permissions` on `main` `f32c41f` (after merged PR13).

- `./gradlew clean build --console=plain` and the CI command `./gradlew --no-daemon build --configuration-cache --warning-mode=fail --console=plain`: **BUILD SUCCESSFUL**; **256 tests, zero failures/errors/skips** (236 before this pass).
- New cases: `ProductionChainTest` (8), `HopperLinkTest` (5), `NinjaTest` (2), `ActionPermissionsTest` (4), `ChunkLimitTest.nearChunk…` (1). `FullCollectionTest` fixtures now grant the default per-action nodes.
- Regression proof: `ProductionChainTest` run against the previous `SpawnerManager` in a temporary worktree: **8/8 failed**; all pass on this branch. Hopper/Ninja/permission tests use new APIs and were not run against old code.
- Artifact SHA-256 is recorded at commit time below; rebuild to compare.
- No schema migration or dependency change. The audit action enum gains `RESTORE` (appended; older log files still parse).

Live Paper/Folia qualification (OWN-13, OWN-17 to OWN-20 in [TESTER_OWNER.md](TESTER_OWNER.md)) is owned by the server operator and is NOT RUN. Mocked schedulers do not prove Folia handling of delayed region tasks in unloading chunks.

# Autosell verification — 2026-11-01

Build: **1.3.1-dev**, autosell ownership changes on baseline `2cfdbb0` (after merged PR11/12).

- `./gradlew clean build --no-build-cache --configuration-cache --warning-mode=fail --console=plain`: **BUILD SUCCESSFUL**, all seven tasks executed, configuration cache reused; **236 tests, zero failures/errors/skips**.
- 23 new `AutosellSchedulingTest` cases. Covers real XP and item currencies (mocked server inventory), owner scheduler execution, one ticket per spawner, disconnect/session replacement, removed/replaced model, changed ownership/currency/config, readiness, shutdown, scheduler rejection/retirement/exception and provider failure. Offline Paper behavior retained; offline Folia makes no provider calls.
- Regression proof: after correcting the serverless Material fixture, 16/17 initial cases failed against the old synchronous implementation. The item-inventory fixture was subsequently corrected to return Bukkit's declared HashMap type. Final targeted and full suites pass.
- Artifact: `build/libs/BravenSpawner.jar`, descriptor version `1.3.1-dev`.
- SHA-256: `faabe4c2c071bc0c8fb003d88e10c5715376b4007609465ca392df70c845d5e3`.
- No schema migration, dependency upgrade or Java/Paper baseline change. AGENTS.md is ignored and absent from Git.

Tests explicitly execute mocked scheduler callbacks and do not prove live region-transfer behavior or third-party API safety. Paper/Folia player/provider/database qualification remains pending. Offline Folia settlement is deliberately deferred until the owner is online. The prior runtime smoke evidence below applies to the older artifact only.

## Previous GUI/build maintenance verification (historical)

# Verification — 2026-11-01

Source: local **1.3.1-dev** maintenance changes on top of `e620f4e` (published 1.3.0). This is an uncommitted working-tree verification, not a new GitHub release.

## Automated validation

- Baseline: 209 tests passed before edits.
- Regression red phase: five GUI cases failed on the synchronous implementation because actions ran before the scheduled tick. After the fix those cases pass; four additional storage invalidation cases cover close, disconnect, revoked access and removed spawner.
- `./gradlew clean build --no-build-cache --configuration-cache --warning-mode all --console=plain`: **BUILD SUCCESSFUL**, all seven tasks executed; **213 tests, zero failures, zero errors, zero skipped**. Configuration cache stored.
- Repeated the same command to verify serialization/reuse: **BUILD SUCCESSFUL**, all seven tasks executed and **configuration cache reused**. No compiler or Gradle deprecation warnings appeared in these clean runs.
- CI-equivalent command `./gradlew --no-daemon build --configuration-cache --warning-mode=fail --console=plain` passed locally (tasks up to date after the clean runs). Workflow YAML parsed successfully; GitHub Actions has not run these unpushed edits.
- Relative Markdown file links checked across README, CONTRIBUTING and docs; no missing targets. `git diff --check` passed.

## Artifact

- Path: `build/libs/BravenSpawner.jar`.
- Packaged descriptor version: `1.3.1-dev`; API declaration `1.21`.
- SHA-256: `f727882514b534c47578a35ba0cd9d700069fdde2720be0b2137639bfc33c75c`.
- Build includes `ShadedSqliteTest`, which opens SQLite through the shipped shaded JAR.
- Build baseline remains Java 21 / Paper API 1.21.1. No dependency-version upgrade or storage-format migration is included in this maintenance pass.

## Runtime evidence and limits

The previous [checkpoint](CONTINUATION_CHECKPOINT.md) records 1.3.0 boot smoke on Paper 1.21.1-133 and 26.2-135, and codec probes. Those runs were **not repeated** for this working tree. No live server, live economy/provider, MySQL/Mongo or Folia qualification was performed in this pass.

The regression tests use mocked schedulers and explicitly execute queued callbacks; they establish dispatch/session behavior, not actual server threading. [CODE_REVIEW.md](CODE_REVIEW.md) records the unresolved autosell ownership risk and other follow-up work. Owner/player acceptance cases remain NOT RUN in [TESTER_OWNER.md](TESTER_OWNER.md).
