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
