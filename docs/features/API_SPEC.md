# Public Developer API & Integration Contracts — API-01

**Status: PROPOSED**. Package names and signatures are conceptual, not present in the published plugin JAR. Target: **1.4.0 API base** = read-only `SpawnerSnapshot` service, cancellable sell/stack events and a `%bravenspawner_*%` PlaceholderAPI expansion. Mutation requests, provider registration and a separate `bravenspawner-api` artifact come later.

## 0. Current implementation (1.2.1)

- **No public API.** There is no API module, no `ServicesManager` registration and no custom Bukkit event (`SpawnerEvents`/`EventEngine` in `events/` are timed in-game boost events, not `org.bukkit.event.Event` subclasses). Other plugins can only reach internals, which is unsupported.
- **Outbound integrations (consumer side):** Vault economy (`econ/Economy`, checks `EconomyResponse.transactionSuccess()`); shop price adapters for EssentialsX, ShopGUI+, EconomyShopGUI, ExcellentShop and zShop (`commerce/`, see [SHOP_INTEGRATIONS](../SHOP_INTEGRATIONS.md)); WorldGuard via `protection/ProtectionService`; Floodgate detection; PlaceholderAPI is **consumed** to render menu text (`platform/Placeholders` → `PlaceholderBridge`), but no expansion is registered.
- All soft dependencies are `compileOnly` and isolated in one bridge class each, so absence does not break startup — the property section 2 requires of the future API.

## 1. Purpose

Allow other plugins to read virtual spawner production, register permitted product/recipe providers and request controlled mutations without gaining access to mutable `Spawner` internals or bypassing atomic inventory / Folia ownership contracts.

## 2. API stability & versioning

- `apiVersion` independent from plugin implementation/build number; semantic versioning plus deprecation period.
- API interfaces in a separate `bravenspawner-api` module, compileOnly dependency for consumers; no `NMS` exposed.
- Define `ServiceLoader`/Bukkit `ServicesManager` registration for host-provided service interfaces; integrations test server/plugin presence before enabling.
- Do not require Vault, MythicMobs, Redis, PlaceholderAPI or shop plugin to load core API.

## 3. Proposed objects

```java
// Illustrative API sketch (NOT production source code).
interface BravenSpawnerService {
    CompletionStage<SpawnerSnapshot> getSpawner(UUID spawnerId);
    CompletionStage<VaultSnapshot> getVault(UUID vaultId, ActorContext actor);
    CompletionStage<ActionResult> request(ActionRequest request, ActorContext actor);
}

record SpawnerSnapshot(UUID id, UUID ownerId, UUID vaultId, long units,
                       long revision, String productKey, String upgradeProfileKey) {}
record VaultSnapshot(UUID vaultId, long revision, Map<String, Long> exactCounts) {}
record ActionResult(UUID operationId, Status status, String reason) {}
```

`CompletionStage` indicates asynchronous service execution; it **does not** grant consumers permission to use Bukkit APIs from callback threads. Define immutable DTOs, validation and ownership context on every mutation. API must enforce same role/claim rules as GUI/commands, not trust arbitrary plugins.

## 4. Events and hooks

The 1.4.0 base ships only the sell and stack/merge events; the rest of this table follows the features it describes. `EvolutionCommittedEvent` and `ContractCompletedEvent` belong to [proposals](proposals/).

| Event | Phase | Cancellable | Scope |
| --- | --- | --- | --- |
| `SpawnerProductionPlannedEvent` | Before plan finalized | Yes, through validated bounded adjustments | Region-owned |
| `SpawnerProductionCommittedEvent` | After durable/committed model update | No | Snapshot-only |
| `SpawnerUnitsMergeRequestedEvent` | Before merge | Yes | Authorization and request context |
| `SpawnerUnitsMergedEvent` | After merge | No | Immutable receipt |
| `VaultTransferRequestedEvent` | Before reservation | Yes | Validated request |
| `VaultTransferCommittedEvent` | After completion | No | Immutable receipt |
| `SpawnerUpgradeRequestedEvent` | Before price/charge | Yes | Validated request |
| `SpawnerUpgradeCommittedEvent` | After upgrade | No | Immutable receipt |
| `EvolutionCommittedEvent` | After fusion/evolution | No | Immutable receipt |
| `ContractCompletedEvent` | After durable reward grant | No | Immutable receipt |

Do not allow asynchronous listener modifications to world state or raw inventory. All cancellable events must execute within the documented scheduler/context and have bounded effect validation. Never double-fire committed events after retries without an event ID and idempotency semantics.

## 5. Integrations

- **Vault economy:** inspect transaction responses; documented unknown-outcome handling.
- **ShopGUI+ / EconomyShopGUI / ExcellentShop / others:** actual price adapters require stable provider API; avoid interpreting plugin existence as integrated pricing.
- **WorldGuard/Lands/Towny:** authorization adapters enforce claim policy on every action; missing adapter must follow server-selected fail-closed rule.
- **MythicMobs:** resolve custom mob definition and loot table via supported API, budget real spawning, isolate unsupported versions.
- **PlaceholderAPI:** read-only cached placeholders, bounded evaluation, no per-placeholder DB query.
- **Redis:** optional cache invalidation/metrics bus. Never treat Redis cache as authoritative inventory or multi-server producer entitlement.
- **Slimefun and custom resource systems:** opt-in commodity or item-variant adapter respecting metadata fingerprints and per-server licensing.

## 6. Extension limits

Product providers declare stable namespaced IDs, drop definitions, sample limits, compatible Minecraft versions, optional XP/recipe IDs and validation hooks. Custom upgrade providers declare maximum levels, exclusive stacking category, price semantics and effect cap. Broken/missing providers disable their own product until recovered instead of zeroing saved stock.

## 7. Error and permissions contract

`ActionResult` includes `SUCCESS`, `DENIED`, `STALE_REVISION`, `CAP_EXCEEDED`, `PENDING`, `RETRYABLE`, `FAILED` with operation ID and localized user-safe reason code. External callers cannot claim admin authority by constructing `ActorContext`; runtime resolves permission from trusted executor context and verifies player/claim identity. Provide quotas for API calls and reject unbounded callbacks.

## 8. Compatibility & tests

- Compile example integration against published API in CI.
- Validate missing/outdated optional dependencies degrade without startup crash.
- Test custom item identity, multi-region request scheduling, event cancellation, delayed async handlers, stale revisions and provider failure.
- Publish Javadocs, migration notes and stable event payload versioning before declaring API production-ready.
