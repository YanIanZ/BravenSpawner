# Virtual Spawner Stacking (VSSP) — Design Specification

**Status: PROPOSED, not implemented.** Stacking producers is distinct from [virtual item storage](VIRTUAL_STORAGE.md). This specification replaces ambiguous interpretations of Bukkit `ItemStack.amount`, PDC virtual counts, and physical block totals.

## 1. Goals and user stories

- A player can merge 100 equivalent Zombie spawners into one placed block without 100 physical mob entities per production cycle.
- A player can split their 100-unit logical producer into 60 and 40 without losing upgrades, ownership, XP or stored items.
- An admin can enforce limits **both** on the number of placed blocks and the virtual producer units within a chunk/world/island.
- The plugin can give, place, merge, pick up and transfer bundles without treating one physical item as one virtual producer when it carries a larger count.

## 2. Distinct quantities and units

| Concept | Technical field | Example |
| --- | --- | --- |
| Minecraft physical item quantity | `ItemStack.amount` (native max stack) | 1 spawner item |
| Spawner item virtual units | `spawnerBundle.units` (bounded long) | 5,000 producers in one item |
| Placed producer aggregate units | `SpawnerGroup.units` | 5,000 producers at one block |
| Number of physical placed spawner blocks | block count | 1 |
| Virtual output stock | `VaultStock.quantity` | 2,000,000 bones |

**Rule:** a single spawner-bundle item has native `ItemStack.amount = 1` and carries the exact virtual producer quantity in validated metadata. When handling ordinary material stacks of vanilla spawner items, apply a separate explicit conversion rule; never silently multiply both fields.

## 3. Stacking modes

1. **SAME_TYPE** (recommended): identical kind/product + compatible producer upgrade profiles can merge.
2. **PROPORTIONAL** (future): compatible upgrades may be converted by a documented, reversible aggregation policy. Must preserve total invested upgrade value or explicitly compensate; no silent tier reset.
3. **OFF**: disable virtual merges for servers that use standalone blocks.

**Non-goal:** merge Zombie with Skeleton into a single homogeneous producer. Mixed-type production belongs to Braven Core networks, not the stacking primitive.

## 4. Canonical group model

```java
// Conceptual API only
record SpawnerGroup(UUID id, UUID owner, String worldKey, int x, int y, int z,
                    String product, String kind, long units, long revision,
                    UUID vaultId, String upgradeProfileId) {}
record SpawnerBundle(UUID issuedToken, String product, String kind,
                     long units, String upgradeProfileId) {}
```

World identification should use stable UUID where possible, with a migration-safe name fallback. Hard caps: per-group virtual units, per-chunk virtual units, per-chunk *placed blocks*, per-island and per-world. All arithmetic is checked and mutations must be authorized at both source/destination claims boundaries.

### Compatibility checks before merge

- Owner or permitted role, world/claim policy, same exact product and kind, source item provenance, non-expired issuance token and allowed target.
- Tier, speed, yield, specializations, traits, catalysts, upgrade tree and processing mode must match the supported compatibility policy.
- If one producer is corrupted or in an outstanding transaction, refuse merge pending explicit reconciliation.
- `newUnits = addExact(oldUnits, incomingUnits)` and every scope cap checked **before** mutating item, group or currency.
- An item can never be consumed while a stale/unconfirmed partial merge is pending.

## 5. Item provenance and duplication defense

PDC fields `kind`, `product`, `units` and `bundle_id` are only hints unless authenticated. Signatures can detect unauthorized edits but **cannot** prevent replay of a legitimately copied signed item.

For high-value virtual producer bundles, use a durable **issued bundle ledger** with single-use/redeemable token and remaining units, plus idempotent operation IDs. The ledger is authoritative for redemption and splits; a copied item with an already redeemed token must be refused without affecting a legitimate group. An issued bundle's token should represent one physical bundle item; merging physical item stacks should not duplicate the token.

Admin-created items, creative cloning, cross-server inventory sync and transfers demand explicit policy. Provide a migration tool for pre-ledger items; do not accept legacy PDC blindly as spendable currency.

## 6. Atomic workflows

### Give or mint

`authorize admin → validate kind/product/units → create durable ledger entry → deliver bundle item through player scheduler → mark delivered`. Ensure pending delivery is reconcilable; do not mint unlimited valid tokens on retry.

### Place

`validate item + issuedToken + claims + caps → reserve bundle units → place physical block (region-owner) → create group and local vault with revision → redeem reservation → acknowledge`.

If place event is canceled or the block is modified by another plugin, release pending reservation; never leave an active producer for a canceled placement. Use a recoverable operation state on server crash.

### Merge

`validate target group + bundle + ownership/permissions + compatibility + caps → reserve exact bundle units → increment group units atomically → consume item entitlement once → mark dirty/commit → render updated GUI`. Any failure returns to a clearly recoverable state. Do not use amount from visible lore.

### Split

`validate amount and owner → reserve units from group → mint a new bundle entitlement with exactly that quantity → deliver or store in pending claim inbox → deduct units and commit`. A split must not automatically duplicate the original group's stored items or upgrade benefits.

### Break

`block break authorization → freeze production + pending transfers → settle local vault per explicit policy → convert group units to a new bundle entitlement → deliver to player or durable claim inbox → delete group and block record once`.

**Recommended default:** on break, stored loot and XP are sent to a durable recovery/claim inbox where possible; if a guaranteed handoff cannot be implemented, refuse the break with an actionable inventory/storage message rather than dropping millions of physical items.

## 7. Production math

Group unit count multiplies logical production units, not physical scheduling/task count. Use the [production engine specification](../TECHNICAL_SPEC.md) for bounded exact/approximate binomial aggregation.

```text
Production cycles: 1
Placed blocks: 1
SpawnerGroup.units: 10,000
Deterministic drop per virtual unit: 2 bones
Virtual vault receives: +20,000 bones
Physical mobs spawned: 0 when VIRTUAL_LOOT enabled
```

For physical mob mode use a configured entity budget and **do not** promise one real mob per virtual unit; server operators select the desired gameplay trade-offs. Always preserve no-overflow and no-duplicate scheduling invariants.

## 8. Upgrade / Fusion / Evolution interactions

Recommended MVP stacking policy is **same-type + same-upgrade-profile** only.

- **Group upgrades:** either apply to all N units with cost scaled by N, or designate an explicit group-level fixed cost with balanced effects. Do not silently treat per-unit costs as one-block costs.
- **Splits:** preserve upgrade profile and units exactly; any special license/trait tokens must follow a defined split policy.
- **Evolution/Fusion:** separate recipes and ledger operations with cost, type transformation, and controlled inventory/output effects; do not reuse generic `merge` logic.
- **Mastery:** define whether counters follow group UUID, owner or virtual unit; prevent infinite repeated masteries through split/remerge.
- **Offline production:** count from an authoritative checkpoint, bounded elapsed time, and unchanged group identity; no unlimited catch-up.

## 9. Virtual stacking GUI and commands

```text
Spawner Control
ZOMBIE | Group units: 10,000 | Stored loot: 2.50M
[ Stack +1 ] [ Stack +64 ] [ Deposit Bundle ] [ Merge All Eligible ]
[ Split 1 ]  [ Split 64 ]  [ Split Custom ]
[ Upgrades ] [ Vault ] [ Production ] [ Permissions ] [ Info ]
```

Proposed commands: `/bspawner stack <units>`, `/bspawner split <units>`, `/bspawner inspect`. These are illustrative and **not implemented**. Reconfirm ownership/claims and exact units when an action executes.

## 10. Upgrade and capacity formula guidance

Recommend declarative caps and cost curves. Large-group upgrades scale by effective virtual units with a configurable economy factor to avoid making giant stacks disproportionately cheap or expensive. Store final quantities as exact integers; prices are validated finite, non-negative and transaction-confirmed (prefer decimal/fixed-point abstraction for price calculations where possible).

```yaml
# Example desired configuration, NOT supported by current releases
virtual-stacking:
  enabled: true
  max-units-per-group: 1000000
  max-units-per-chunk: 2000000
  max-physical-blocks-per-chunk: 64
  merge-policy: SAME_TYPE_AND_UPGRADE_PROFILE
  allow-creative-bundle-redemption: false
  bundle-provenance: ISSUANCE_LEDGER
```

## 11. Migration

Current `Spawner.stack` is an `int` and item metadata carries a separate stack value while placement uses 1. A future migration must:

1. Snapshot current DB, worlds, item bundles, config, and audit inconsistent PDC records.
2. Convert valid `Spawner.stack` values to bounded group units, retaining group UUID/owner/tier and preserving inventory/XP.
3. Enroll valid old bundle items via explicit audited redemption/migration policy; flag ambiguous legacy bundles.
4. Reconcile group/block indexes, unique constraints and provenance ledger.
5. Test reverse compatibility/rollback on a copy; block downgrade to a schema unable to represent `long` units.

## 12. Acceptance and owner tests

- Place, merge, split, pick up and re-place 1 / 64 / 5,000 / 1,000,000 units; sum must remain exact.
- Try identical-looking item with different kind, upgrades, owner, or invalid metadata; merge denied without cost.
- Clone a redeemed bundle token: replay rejected; original rightful producer count unchanged.
- Two players merge concurrently with cap nearly reached: only valid transfer(s) succeed and never exceed cap.
- Near `long` bounds and negative/zero values: checked rejection, no wraparound.
- Break with local vault holding millions of items: no materialized item flood and no lost XP.
- Crash/restart during pending split/merge/placement; reconcile with operation ledger.
- Folia cross-region GUI/permission, plus separate world/chunk and claim-limit checks.
- Confirm exactly one production task for the physical placed block at high logical unit counts.

See [TESTER_OWNER.md](../TESTER_OWNER.md) for execution logging.
