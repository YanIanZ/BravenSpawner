# Virtual Storage System (VSS) — Product and Engineering Specification

**Status: PROPOSED, not implemented.** Target: **1.4.0 (Virtual Vault MVP, STOR-01)**; network vault and advanced UX come later. Initial design 2026-10-30, synchronized against shipped 1.3.0 on 2026-11-01. See the [technical specification](../TECHNICAL_SPEC.md) and [GAP_AUDIT §4.4](../GAP_AUDIT.md).

## 0. Current implementation (1.3.0 baseline)

What STOR-01 migrates from:

- `model/Spawner.java` keeps stock as `List<ItemStack> storage` behind the spawner monitor. `storeVirtual(ItemStack, int maximumItems)` already keeps **one logical entry per item identity** (`isSimilar`) independent of vanilla stack size, but counts are `int` and the ceiling is a total item count (`plugin.maximumItems(spawner)`), not distinct keys.
- Production (`SpawnerManager.produceVirtual`) inserts via `storeVirtual`; XP is a separate `int storedXp` capped by `maximumXp`.
- Persistence: the list is serialized with `Storage.Codec.encodeItems` (BSC2 codec v2: separate logical amount and amount-one Paper byte template → Base64; legacy Java streams are read for migration) into the `items` column of `braven_spawners` (`SqlStorage`, schema version 2 via `braven_schema_version`; v2 added `corrupted_at`). YAML and Mongo (`SpawnerDocumentCodec`) use the same item codec. Checkpoints are dirty-only and batched (`CheckpointService`, per-spawner `revision`).
- Withdrawals: `SellService` quotes then drains through per-identity `Reservation`s with a `QUOTED→PAID→CLEARED|FAILED` state machine and checks the selected currency transaction result. `HopperLink` (every 8 ticks, region scheduler) and the GUI take-one/take-stack/take-all actions (`gui/Withdrawals`) mutate the list directly under the spawner lock.
- Storage init failure fails closed (safe mode) unless `databases.yml: fallback-to-yaml` is set.

Gaps this spec closes: `long` counts with checked arithmetic, `max_distinct`/`max_total` semantics, a versioned `ItemKey`, key-based reservations shared by sell/hopper/GUI/wand, and a separately versioned vault migration with backup (VSS-09); the shipped BSC2 item codec v2 is not an exact-count vault.

## 1. Why virtual storage?

The current `Spawner` already merges similar output into logical `ItemStack` entries, rather than allocating one object per vanilla stack. Its in-memory counts and limits remain `int`. The proposed vault separates item identity from exact `long` quantities and coordinates reservations across consumers; this is a precision and accounting change, not a claim that current storage allocates 15,000 objects for a million identical items.

**Two distinct meanings of "stack" are mandatory:**

1. **Spawner stacking:** One placed producer represents N identical virtual producers; N affects resource output, caps and upgrade policy.
2. **Virtual item stacking:** One logical `ItemKey` maps to an exact quantity `long` across a storage scope; the GUI displays a representative item and count, not N physical Bukkit stacks.

These systems must never share a single ambiguous `stack` field, and **stored item count is not the same as inventory slot count**.

## 2. Player-facing storage experience

- **Spawner Vault (local):** every physical spawner owns its virtual storage, XP bank and independent upgrade tiers.
- **Braven Core Vault (network; [proposal](proposals/BRAVEN_CORE.md), not scheduled):** authorized spawners can deposit into a shared controller-owned vault; deposits and withdrawals must be mediated by a versioned transaction service, never by directly mutating two maps across Folia regions.
- **Modes (configurable):** `LOCAL_ONLY`, `NETWORK_OUTPUT` (local buffer -> network), and `NETWORK_SHARED` (network owns stock after confirmed transfer). Network mode is **not** implicit cross-server sync.
- **Visibility:** overview shows capacity, top materials, last cycle, production/hour, filters, XP, network connection status, and last durable save.
- **Item view:** search by item name/material, sort by quantity/value/name, filter by category, preview exact amount, withdraw 1 / 16 / 64 / custom / max that fits, and sell with confirmation.
- **Accessibility:** clickable inventory controls with readable lore and title, text-only commands as fallback, no required packet/NMS UI, distinguish disabled buttons and permission refusals.

## 3. Capacity semantics

Three independent limits, never overloaded:

| Field | Meaning | Example |
| --- | --- | --- |
| `max_distinct_items` | Number of different canonical item keys/variants | 64 material/variant entries |
| `max_total_items` | Sum of all virtual quantities in vault | 5,000,000 items |
| `max_per_item` | Maximum quantity per key | 2,000,000 Rotten Flesh |
| `max_withdraw_per_action` | Rate and inventory safety limit | 2,304 items (36 x 64) |
| `max_storage_bytes` | Serialized payload safety bound | implementation-defined |

All are optional admin-defined constraints subject to a **hard technical bound**. A configured unlimited mode removes gameplay caps only; `long`, DB payload size, RAM, and processing limits still apply. Reject overflow using checked arithmetic; do not use `int` or floating point for stored item quantities. Display compact quantities (`2.5M`) plus exact quantity in hover/lore. Capacity is measured in **items, not Bukkit slots**, unless configured to include a separate distinct-key cap.

### Example

```text
Blaze Spawner — Local Vault
BLAZE_ROD       2,500,000
BONE              75,000
IRON_INGOT        12,450
-------------------------
3 distinct keys | 2,587,450 items | 51.7% of 5M capacity
```

A 54-slot inventory is **only a paginated view** of a much larger logical vault.

## 4. Canonical item identity

Use a well-defined `ItemKey`:

- For ordinary stackable materials with no special metadata, key by Bukkit `Material` and canonical vanilla component identity.
- For metadata-bearing items, serialize/normalize **all relevant** components (custom model, enchantments, damage, display name, PDC and versioned data) to preserve equality semantics. Never group distinct custom items solely by `Material`.
- For non-fungible or ambiguous custom/plugin items, either keep individually versioned records or **reject them from VSS** and allow the legacy storage path; do not silently erase metadata.
- Whitelist approved item classes/materials for MVP, version the fingerprint algorithm and retain a lossless representative serialized item. Handle game-version migrations explicitly.
- No deserialization error becomes an empty legal store. Quarantine invalid items and block destructive rewrite.

```java
// Conceptual API only — not compiled production code
record ItemKey(String formatVersion, String canonicalFingerprint) {}
record StockEntry(ItemKey key, long quantity) {}
record VaultSnapshot(UUID vaultId, long revision, Map<ItemKey, Long> items) {}
```

### MVP item policy

`VIRTUAL_STORAGE_COMMODITIES` supports vanilla stackable products in configured drop tables first. Advanced/custom items must be explicitly supported by a separate variant/individual-item feature with tests.

## 5. Atomic operation model

All public actions route through an authorized `VaultService` with actor identity and vault revision. The service must support:

- `deposit(vault, itemKey, amount, idempotencyKey)`
- `reserve(vault, itemKey, amount, operationId, expiry)`
- `commitReservation(operationId, acceptedAmount)`
- `releaseReservation(operationId)`
- `transfer(source, destination, items, operationId)`
- `sell(vault, quotedStock, economyProvider, operationId)`
- `snapshot(vault)` (immutable)

**Conservation invariant** per item key:

```text
beforeStock + generated + deposits
= afterStock + deliveredToPlayer + soldConfirmed + consumedByRecipe
  + acceptedNetworkTransfers + explicitAdminRemoval
```

Count source/destination transfers exactly once (not in both sides of an aggregate total). Each count-changing operation is revisioned, durably journaled before external acknowledgement as required by the chosen durability contract, idempotent across retries, and returns an auditable result.

### Player withdrawal across Folia contexts

Player inventory belongs to an entity scheduler, while vault state is owned by a region/service context. Never assume an in-memory lock provides ACID across both. First reserve exact stock, run a bounded player-inventory insertion on the player scheduler, determine **actual** accepted amount, then commit accepted count and release remainder. Persist pending operations and reconcile on interruption. The implementation must choose a recoverable handoff/escrow design for crashes between inventory mutation and durable acknowledgement; if it cannot guarantee that handoff, do **not** advertise crash-proof withdrawals. Avoid dropped-item fallback unless explicitly configured and region-owned.

### Automation, hopper and sell

- Hopper requests a bounded amount; reserve → insert into live inventory on owning region → finalize accepted count, never trust a stale `BlockState` snapshot alone.
- Auto-sell uses real priced-item reservations and checks `EconomyResponse`; unknown provider outcome enters reconciliation, not a blind retry.
- Filters are applied before production insertion/transfer and are validated again at commit.
- Item overflow choices: `PAUSE_PRODUCTION` (safe default), `AUTOSALE_IF_CONFIRMED`, `LOCAL_BUFFER`, and `VOID_WITH_EXPLICIT_WARNING` (admin enabled). No silent data deletion.
- `AUTO_CRAFT` is downstream of a versioned virtual recipe engine; consume inputs and produce output in one atomic vault transaction if all local.
- Admin can restrict material types, rate, items per operation and number of expensive GUI queries.

## 6. Persistence schema and indexing (proposed)

```sql
-- Illustrative logical schema, not a migration script:
vaults(vault_id UUID_PK, scope, owner_id, network_id, revision BIGINT, policies_version)
vault_stock(vault_id, item_key, quantity BIGINT NOT NULL, item_representation BLOB,
            PRIMARY KEY (vault_id, item_key))
vault_reservations(operation_id UUID_PK, vault_id, item_key, reserved BIGINT,
                   state, expires_at, source_revision)
vault_ops(operation_id UUID_PK, actor_id, operation_type, state, created_at, completed_at)
```

Use backend-appropriate UUID/BLOB representations and check constraints. A `BIGINT` exact counter is adequate only under **explicit configured bounds**; totals use checked addition, never truncate. Design a consistent snapshot API so production writes can be batched, with SQLite for single-server and MySQL when remote persistence is needed. Store SQL transaction/revision metadata and WAL checkpoints with metrics. Redis may cache *immutable snapshots / invalidations* but must never be authoritative.

### Local vs Network

- Local vault writes are scoped to a single producer's serialized state machine.
- Network vault requires a clear writer/owner and fencing lease. If two regions contribute, use asynchronous reservations and a server-authoritative network ledger; no directly shared mutable Bukkit objects.
- Across server instances, network deposits/withdrawals are **not allowed** without durable single-writer ownership, generation-based fencing and tested recovery from split brain.
- Network partition behavior is **read-only/fail closed** for unauthorized/unreconcilable writes.

## 7. GUI layout proposal

```text
[ Overview ] [ Storage ] [ Auto Rules ] [ Network ] [ Statistics ]
[ Search: Material / Name ]  [ Sort: Quantity / Value / A-Z ]
[ Rotten Flesh ×2.50M ] [ Blaze Rod ×45.6K ] [ Diamond ×1.20K ]
[ Select item ] -> Withdraw: 1 | 16 | 64 | Custom | All that fits
[ Prev ] [ Capacity / Items ] [ Next ] [ Back ]
```

GUI entries are visual projections; `ItemStack.amount` remains within native limits. Every click uses a fresh transaction + authorization check. Never trust amounts from lore or stale GUI state.

## 8. Upgrade and integration contract

- **Capacity:** increase total allowed items and/or distinct item entries (separate configurable curves).
- **Compression:** unlock tiered capacity efficiency; it does **not** fabricate item counts or change the economy value.
- **Transfer:** increased *rate* of controlled output to network/hopper; no bypass of underlying destination inventory.
- **Overflow Policy:** unlock more automation branches; explicit admin override for voiding items.
- **Storage Insight:** display rolling fill rate and ETA computed from observed rates, with uncertainty caveat.
- **Ownership:** player roles `OWNER`, `MANAGER`, `OPERATOR`, `WITHDRAWER`, `VIEWER`; access to sell/network settings separate from withdrawal rights.

See [VIRTUAL_STACKING.md](VIRTUAL_STACKING.md) for producer-count semantics. Release split: **1.4.0** ships the local vault (sections 3–6 local scope, VSS-01/03/04/08/09); the GUI search/sort and withdraw-amount picker land with the 1.5.0 UX base; the Braven Core network vault is a [proposal](proposals/BRAVEN_CORE.md) and not scheduled.

## 9. Acceptance and owner test scenarios

| ID | Required case | Pass criterion |
| --- | --- | --- |
| VSS-01 | Produce 10,000,000 identical commodity items | Correct exact count, bounded memory, no 156,250 `ItemStack` entries |
| VSS-02 | Mixed custom metadata on same material | No merging of differing variants or lost metadata |
| VSS-03 | Max capacity and full-hopper attempts | Rejected items remain in source; no silent overflow |
| VSS-04 | Concurrent production, GUI withdrawals, sell | Count conservation and no double settlement |
| VSS-05 | Crash between reservation and delivery | Auditable recovery and no duplicated credited result |
| VSS-06 | Disconnect, teleport, page spam, inventory drag | No stale authorization/session or phantom transfer |
| VSS-07 | Local -> Braven Core -> local transfer | Exactly-once accounting within supported failure model |
| VSS-08 | Invalid config/negative/overflow quantity | Fail closed, no overwritten existing data |
| VSS-09 | Import legacy `List<ItemStack>` and rollback | Equal source/target counts, untouched original backups |
| VSS-10 | Folia multi-region hopper/player interactions | No illegal thread/world/entity access |

The owner test report must record actual versions, metrics and failure-injection evidence per [TESTER_OWNER.md](../TESTER_OWNER.md).
