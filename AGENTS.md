# BravenSpawner repository guidance

## Project and source of truth
- Java 21 Paper plugin; package `dev.yanianz.bravenspawner`. Folia support requires region/entity ownership discipline and live qualification.
- Read `build.gradle.kts`, `CHANGELOG.md`, and `docs/CONTINUATION_CHECKPOINT.md` for the current implementation. Use `docs/VERIFICATION.md` and `docs/CODE_REVIEW.md` for current validation; `docs/AUDIT.md`, `docs/GAP_AUDIT.md`, and `docs/FEATURE_PARITY.md` retain historical snapshots. Verify claims against source.
- Feature designs in documentation PRs are proposals, not shipped functionality. Current storage uses `List<ItemStack>`; exact-count Virtual Vault and authoritative bundle issuance remain future work.

## Build and validation
- Run `./gradlew build --console=plain`; use `./gradlew clean build --console=plain` when a fresh artifact is needed.
- Gradle selects a Java 21 toolchain. Output: `build/libs/BravenSpawner.jar`.
- Tests: `src/test/java/dev/yanianz/bravenspawner`; XML: `build/test-results/test`. The build includes a SQLite test against the shaded JAR.
- Compilation and mocked tests do not qualify live Folia, player interactions, economy providers, MySQL or MongoDB. Record actual runtime evidence separately.

## Code map and invariants
- `manager/`, `model/`, `product/`: lifecycle, production and stock. `storage/`: YAML/SQLite/MySQL/Mongo persistence and codecs.
- `gui/`, `menu/`, `listener/`: sessions, presentation and player/block actions. `econ/`, `sell/`, `commerce/`: currencies, settlement and pricing integrations.
- `src/main/resources`: shipped configuration, menus, messages and mob definitions.
- Preserve fail-closed storage, authorization, stock/payment conservation, and single-owner mutation. Schedule world/entity operations on their owning region/entity.
- Follow Java 21, four-space indentation and existing conventions. Read `CONTRIBUTING.md`; reflection is limited to guarded optional integration adapters. GUI callbacks must run after the click event and reject stale windows.
- Treat `ref/` as read-only reference evidence; do not bundle reference classes. Do not commit generated JARs, caches or live-server data.

## Memory
- Bind OmniMemo explicitly to this Git root: `/Volumes/Ext/REA/BravemSpawner` (directory spelling differs from the product name).
- Use scoped `om context` and concise checkpoints. Never select another project or initialize replacement storage implicitly.
