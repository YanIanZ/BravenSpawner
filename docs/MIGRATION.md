# Migration, Compatibility and Recovery Guide

**State:** Preparatory plan for future releases; **not** an executable migration guide for unreleased features.

## 1. Current deployment baseline (1.3.0 / 1.3.1-dev)

- Build target: Java 21 and Paper API 1.21.1. Prior Paper 1.21.1/26.2 smoke results are recorded in the checkpoint; full version/Folia qualification remains open.
- YAML, SQLite, MySQL and MongoDB use Braven-owned schemas. SQL schema v2 adds corruption timestamps; SQL checkpoints batch dirty rows.
- Storage initialization/load failures fail closed by default. Explicit `fallback-to-yaml` is an opt-in behavior, not the default recovery path.
- Item codec BSC2 v2 writes logical amounts separately from amount-one Paper byte templates. Legacy streams are readable; older plugin versions must not be assumed to read newly saved v2 blobs. Rollback requires a compatible backup, including server-version-compatible item data.
- 1.3.1-dev changes GUI scheduling and build configuration without introducing a storage format migration. The unused `instant_page_navigation` key is removed from bundled defaults; old copies are ignored, and GUI actions always use the next player tick.
- Exact-count Virtual Vault is proposed for 1.4.0 and requires its own format/schema migration. Do not confuse SQL schema v2, BSC2 codec v2 and that future migration.

## 2. Pre-upgrade checklist

1. Stop gameplay writes; schedule a maintenance window and notify players.
2. Record plugin JAR hash, server build, Java version, `config.yml`, `databases.yml`, generated `hooks.yml`, plugin integrations and installed economy.
3. Stop the server cleanly. Verify the latest persisted data is consistent; **do not assume the in-memory records were saved after a crash**.
4. Back up all BravenSpawner files and the SQL database using engine-appropriate consistent backup. Include SQLite `.db` plus required WAL/shm state or use SQLite's backup API.
5. Restore the backup to a staging server. Measure owner/spawner/item/XP totals and check for malformed records.
6. Test the proposed JAR against identical data in staging before production.
7. Confirm that all dependent plugins and server variants support the new Java/API target.

## 3. Future vault schema transition (proposed)

**Stage A:** Add schema version and new columns/tables without destroying old rows; preserve UUID and world/coordinates.

**Stage B:** Read old records with compatibility adapter; validate and quarantine records with unknown materials or corrupt inventories instead of quietly zeroing them.

**Stage C:** Run transactional migration and reconciliation on staging; record row totals, virtual stack totals, stored XP and material quantities before/after.

**Stage D:** Enable new dirty writes and journal only after post-migration validation; retain a read-only backup and document forward-only migration cutover.

If validation fails, remain offline/read-only, restore backup and investigate. Do not auto-restore blocks or overwrite unrelated world changes.

## 4. Java/server compatibility strategy

Keep the Java 21/Paper 1.21.1 compile baseline for this maintenance pass. A future API/JDK upgrade needs a separate tested server matrix and explicit minimum runtime per artifact. Do not equate a 90-mob catalogue or a successful newer-Paper boot with complete gameplay compatibility.

## 5. Rollback and recovery

- Take application offline; do not mix old and new plugin versions with concurrent writes to the same database.
- Restore both database **and associated world snapshot** from consistent points where possible.
- Restore config and plugin version together; a newer schema may not be backward-readable without an explicit down-migration.
- Check UUID duplicates, block collisions, item totals, XP totals, player balances and payout journal status.
- Preserve pending or uncertain economy transactions for manual reconciliation; do not blindly retry payouts.
- Collect logs/DB status and annotate the failed release in the owner test report.

## 6. Multi-server note

MySQL shared across servers is **not** proof of multi-server correctness. Until explicit leasing/fencing and idempotent operations are designed, one authoritative writer must own each spawner. Redis, when introduced, is a cache/event bus, not the source of truth.

See [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md) and [TESTER_OWNER.md](TESTER_OWNER.md).
