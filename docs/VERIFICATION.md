# Verification — 2026-10-30

Current source: main checkout, `1.2.0-dev`. Latest `./gradlew build --console=plain` succeeded with **82 tests, zero failures, zero errors, zero skipped**. An earlier clean build also succeeded. `git diff --check` passed. Gradle reports deprecated API/features; these are not compilation failures.

Artifact: `build/libs/BravenSpawner.jar`, also copied to `artifacts/integrated-20261030/BravenSpawner.jar`.

SHA-256: `23ef521890d925bd8855fa9f26f17336216e5f0f4d2f03139dea8fae8e7aa7e7`.

The descriptor contains version `1.2.0-dev`. The ledger implementation is packaged. No original VirtualSpawner package classes are bundled. Machine-readable source hashes and test totals are in `artifacts/integrated-20261030/verification.json`.

Five new booster persistence tests exercise restart deadlines/scopes, permanent autosell, personal-effect exclusion, invalid/corrupt ledger rejection and preservation, empty snapshot replacement, and nonfinite multiplier/invalid scope rejection. Existing tests cover isolated rules and mocked integration boundaries. They do not exercise a live game server, database or provider. Read FEATURE_PARITY.md for scope and known gaps.
