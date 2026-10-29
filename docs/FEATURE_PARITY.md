# VirtualSpawner reference parity audit

Static audit dated 2026-10-30. This is an evidence inventory, not a claim of runtime equivalence. “Implemented” below means an identifiable source path exists and is called; it does not mean tested on Paper, Folia, or with third-party plugins. Source wiring was reconciled after the current integration changes. Runtime qualification remains separate.

## Artifact provenance

- Supplied artifact: `/Users/rheninxy/Downloads/VirtualSpawner-7.2.0-rc-12.jar`.
- SHA-256: `803bfcce299b5b5010afd575970e9253c69f8b232ae791ab313bfabd33c7ea2e`.
- Analyzed working copy: `$TMPDIR/re/virtualspawner-parity/VirtualSpawner-7.2.0-rc-12.jar` (Python's platform temp directory).
- ZIP inventory: 620 entries: **457 classes, 58 resources, 105 directories**. There are **36 bundled mob YAMLs**, one itemspawner YAML, five GUI YAMLs, two booster YAMLs, and the config/database/hooks/hologram/sellwand/modifier/sound/other files.
- Every one of the 515 non-directory JAR paths has a corresponding `ref/` file. **486 hashes match; 29 differ**, all `.class` paths. All 58 resource files hash-match. The mismatches are case-colliding obfuscated class filenames, so `ref/` is not a safe executable/class-byte oracle on this filesystem. Class analysis uses `javap -classpath <jar>`, never the extracted collision-prone classes.
- Supporting parent artifact inspection receipt: `ev_fe35bd83184aeba9eecc6683cc1240e20540c890aaf1386764fcf25797396f0a`. Its 516-file report differs by one from the ZIP's 515 actual file entries; ZIP inventory is the counting authority here.

## Critical semantic findings

### Stock means generated inventory, not a production reserve

**Seen:** `net.codava.virtualspawner.gui.impl.StockChest` reads `VirtualSpawner.getContent(Material)` / `IStorage.getAmount`, displays material stacks, and reconciles withdrawals through `collectItem(Material,int)` and `removeAmount(Material,int)`. Its slot states bind material, displayed amount, and session UUID. `StockMenu` invokes `takeOne`, `takeStack`, and `sellStock`. `ref/config.yml:disable-item-stock` and `ref/gui/storage.yml` belong to this generated-inventory surface.

The separate local `product/Stock.java` and `StockService.java` describe a replenishing production reserve with `stock.max-stock` and `stock.restock-per-cycle`. Those keys do not occur in the supplied default product resources. **No reference evidence establishes that reserve mechanic.** Do not wire it into production or advertise it as reference stock parity without further evidence. Local `Spawner` stored items and the storage GUI are the closer analogue.

### Buy stacks uses each product's price

**Seen:** reference `BuyStackMenu.generateInv` obtains `SpawnerConfigData.getSpawnerPrice()` and `getStackLimit()`. `buyStacks` limits quantity by remaining stack space and `SpawnerManager.getChunkHeadroom(Location)`, checks `CurrencyManager.getBalance`, then `withdraw`, `VirtualSpawner.addStack`, and `save`. `ref/gui/buy_stack.yml` exposes buy 1, buy 64, buy maximum, and back; `ref/mobs/zombie.yml` sets `spawner-price: 15000`.

The active `SpawnerGui.buyStack` now uses per-product price, product/global stack cap, chunk headroom, economy withdrawal and dirty marking. `stock/BuyStackService.java` retains a separate global-price service; it is not the active configured-menu purchase path. Failed payments and concurrent purchases still need live-server qualification.

### Mob spawners generate virtual drops

**Seen:** reference `SpawnerGenerator.generateDrop` constructs a cancellable `AsyncSpawnerGenerateEvent` containing drop types and amounts, then updates `SpawnerData.getOrCreateStorage(Material)` / `IStorage.setAmount` under a storage cap. No world entity-spawn call appears in the inspected generator class. This is strong static evidence for virtual drop generation; it is not proof of every runtime code path.

The current `SpawnerManager.produceVirtual` now generates configured loot directly for both mob and item products through `ProductRoller`, with item/XP caps and dirty marking. The earlier physical-mob generation divergence was corrected. `EntityListener` remains for legacy tagged entities; no new physical mob production path is active. Exact distribution/performance equivalence and cancellation through reference API events are not established.

### Resource presence does not establish resource compatibility

Local `ProductLoader` now feeds `ProductRegistry` into startup/reload settings and parses numbered drops/amount ranges/percentage chances, XP min/max, delay min/max, storage item/XP caps, maximum stack, and product purchase price. The production scheduler now resamples the delay each cycle, and `storage.must-be-full-to-collect` is parsed. Custom display/model/ItemsAdder/drop priority/per-drop price metadata remain incomplete. The unused replenishing reserve remains a local optional concept, disabled by default for files without `stock.*`.

The repository now has **40 mob defaults**: the existing 12 were preserved, and 28 missing reference types were independently authored from operational scalar evidence. This covers all 36 reference IDs plus four existing local IDs. The new resources contain no copied texture URLs, lore, original comments, or branding. Existing local defaults are intentionally not exact copies of the reference tuning. YAML parsing, scalar comparison and unchanged hashes for the existing 12 were checked; this is not runtime validation.

### Storage caps and the full flag: exact static contract

**Seen:** `SpawnerData.getContentAmount()` streams all material-storage values and sums amounts. `VirtualSpawner.getMaxStorage()` multiplies `SpawnerConfigData.getStorage()` by `SpawnerData.getStack()` and caps at `Long.MAX_VALUE - 1`; `getMaxXP()` similarly multiplies per-product XP capacity by stack and caps at 2,147,483,646. The generator compares total content against this global cap, not a separate cap per material and not a vanilla stack of 64.

A direct ZIP constant-pool scan finds `isFullToCollect` only in its config interface/implementation and `VirtualSpawner`. In VirtualSpawner the flag gates `performSell`: total generated loot must reach `getMaxStorage()`, and `sellXP`: collected XP (after the personal XP multiplier) must reach `getMaxXP()`. Inspected `SpawnerInventoryManager.collectItem`, `takeOne`, `takeStack`, `takeAll`, `StockMenu`, and `StockChest` do not call this getter. Their withdrawal paths check available requested material and transaction/session conditions. Therefore blocking raw item withdrawals until full would add a restriction not supported by these reference paths. Unlimited-storage sentinel behavior and multiplied XP edge cases require runtime qualification.

### Corruption puzzle rules

**Seen:** `CorruptionMenu.click` compares the clicked slot's index in `menu.puzzle_slots` with per-player `currentIndex`. The expected slot turns correct and advances; a later slot resets every puzzle pane and index to zero; an earlier solved slot or a non-puzzle slot is ignored. Completion closes the inventory, clears the original spawner, and clears spawners returned by `SpawnerUtil.getVirtualSpawnersInRadius(player, solveRadius)`. That utility uses an inclusive integer sphere around the player's **block coordinates**, not the origin spawner: dx² + dy² + dz² ≤ radius². It finds registered UUID-tagged real spawner blocks and applies no ownership filter. Correct/incorrect/completion sounds are configured; exact local rendering and lifecycle still require runtime qualification.

## Feature inventory

| Area | Reference evidence | Local source evidence and status at audit |
| --- | --- | --- |
| Placement, break, conversion, stacking, ownership/trust | `SpawnerListener`, `VirtualSpawner`, `SpawnerPlaceEvent`, `SpawnerStackEvent`; config ownership, limits, full-stack, conversion, silk-touch, durability/explosion flags | `BlockListener`, `StackService`, `Spawner`, command trust path. Implemented core paths; exact permissions, cancellation, count conservation, and third-party land protection need runtime qualification. |
| Virtual generation, per-product delays/XP/drops | `SpawnerGenerator`, `SpawnerConfigData`, all `mobs/*.yml`, `itemspawners/diamond.yml` | Both product kinds now use direct virtual configured drops. Product ranges/caps/max-stack/price are parsed and connected. Delay is resampled each cycle and the collection-full flag is parsed; metadata and exact collection-policy integration need qualification. |
| Stored loot, XP, take one/stack/all, sell/drop, pagination | `StockChest`, `StockMenu`, `gui/storage.yml`; generated material amounts in `IStorage` | `SpawnerGui`/`Spawner` provide paginated storage, collection and XP. Exact reference action/layout behavior and overflow handling remain unqualified. Reserve `StockService` is a separate local mechanic. |
| Main menu, stack purchase, GUI customization | Five reference `gui/*.yml`; `SpawnerMenu`, `BuyStackMenu`, `CorruptionMenu`, `LogMenu` | `SpawnerGui` now owns `MenuService` and routes configured main/storage/purchase actions; fallback layouts remain. Active stack purchases use per-product price and chunk/stack limits. Exact reference layout/action compatibility remains partial. |
| Session/dupe protection | Reference StockChest session UUID/slot reconciliation, quit/move/teleport handlers; `chest_gui.protection` config | `GuiLock`, generation-bound `SpawnerGui` holders and `GuiGuardListener` exist. No live-server exploitation/concurrency test performed. |
| Selling, autosell, currency | CurrencyManager/ShopManager APIs; Pre/PostSpawnerSellEvent; `hooks.yml` Vault/SuperMobCoins | Local `SellService` has payout state/claim logic and Vault settlement; `ShopHook` is explicitly informational. SuperMobCoins/custom currency registration is not implemented. Autosell booster parity is not established. |
| Shop pricing | Direct JAR bridges for ShopGUI+, EconomyShopGUI, ZShop, ExcellentShop, DonutWorth, EssentialsX, Nascraft | `commerce/adapters/*` detect five shop families but inherit `apiPresent=false` and `priceOf=-1`. **Stubs/detection, not functional shop price integrations.** DonutWorth/Nascraft only appear in informational ShopHook detection. Local sell-price fallback is the real price source. |
| Boosters | `boosters/normal.yml` and `personal.yml`: speed, XP, loot, autosell, duration/items | `Boosters` provides player/spawner multiplier maps and timed expiry. Separate reference speed/XP/loot/autosell types and booster-item workflow are not implemented by a single multiplier. |
| Sell wands, modifiers, sound/particles | `sellwand.yml`, `modifiers.yml`, `sounds.yml`, reference SellWand command and SellWandEvent | `wand/*` PDC wand/modifier/lifecycle/listener/sound logic is now installed at bootstrap, with sellwand/modifier grant commands. Full modes/visual/reference-config parity remains unqualified. |
| Corruption and repair game | `CorruptionMenu`, `gui/corruption.yml`, config radius/delay/breaking | `CorruptionTask` and command repair exist. Task is started; reference minigame/menu is not the command repair equivalent. |
| Holograms/toggle | `holograms.yml` enables provider type, multi-line content, progress bar, radius, corruption formatting; `SpawnerHologram` | `Holograms` now consumes HologramConfig multiline output/view distance through native TextDisplay. Reference external DecentHolograms/FancyHolograms providers are not implemented; timing/radius/corruption formatting equivalence is not established. |
| Bedrock layouts, placeholder substitution/formatting | `bedrock`, `placeholders-in-gui`, storage layouts; `other/internal_placeholders.yml` | `PlatformDetector` and Floodgate probe are installed; the configured main menu chooses a Bedrock layout. PAPI bridge and PlatformText exist, but end-to-end substitution coverage remains unqualified. PAPI input substitution is distinct from a registered `%virtualspawner_*%` expansion; no such local expansion found. |
| Ninja visibility, hoppers, mending, restore missing | Config flags and corresponding reference classes/config surface | `Ninja` and `HopperLink` are started by BlockListener; Mending is used by collection; RestoreMissing runs after storage load. Static paths implemented; Paper/Folia scheduling, unload/reconnect and restoration must be exercised. |
| Logger and log menu | `logger/Log`, `LogType`, `gui/log.yml`, enable-logger | `audit/*` contains writer/parser/menu support, but no production AuditLog/AuditMenu construction found at audit. AuditMenu references root `log.yml`, whereas bundled file is `gui/log.yml`; wiring/path alignment needed. |
| Persistence, checkpoints, migration | `databases.yml` documents SQLite/MySQL/MongoDB; DatabaseManager/IDatabase/LoadWorker and backend classes | YAML, SQLite, MySQL and checkpoint code exist; MongoDB is not implemented. Existing reference database schemas/serialized objects have not been proven readable. ProductMigration targets local embedded configs, not reference DB migration. |
| Timed global events | `other/events.yml`: calendar/season/time windows, weighted triggering, duration/concurrency, XP/cash multipliers, messages and start/end commands | No matching local global event scheduler/config consumer found. Resource surface establishes intended capability; exact reference runtime semantics remain unverified. |
| Restricted spawner types | `other/restricted_spawners.yml`: lockmode, allow-take-items, restricted list | No corresponding local config consumer found. |
| Public extension API/events | `api/VirtualSpawnerAPI`, permission/currency/shop hooks, manager interfaces, cancellable place/break/stack/menu/generate/sell events | No compatibility package `net.codava.virtualspawner.api` exists locally. Internal BravenSpawner getters are not a compatible public API or event surface. |
| Protection/skyblock/chunk loading | plugin softdepends list; PermissionHook; explicit SuperiorSkyblock multiplier/permission hook and WildLoaders hook classes | Local descriptor lists WorldGuard/Lands/Towny but no functional protection-hook invocation found. SuperiorSkyblock multipliers/role checks, other listed protections, and WildLoaders integration are not implemented. Dependency declaration alone is not integration. |
| Versions/Folia | Reference `versions/` includes 1.16.5 through 1.21 variants and v26 variants; `folia-supported: true` | Local Java 21/Paper 1.21.1 compile target, Scheduler helpers, Folia descriptor. No claim of reference legacy-version coverage. Native TextDisplay itself needs modern server support. |
| Commands/permissions/identity | `/vspawner` aliases `/virtualspawner`, `/vs`; `/vmodify`, `/shtoggle`; `vspawner.*` | Local `/bspawner`, `/bmodify`, `/bhtoggle`, `bravenspawner.*`. Intentional identity differences require documented migration; original command/permission drop-in compatibility is not present. |

## Verification boundary and next qualification

No server was launched and no reference plugin was executed for this audit. JAR signatures, resource hashes, and bytecode call sites establish static capabilities only. No successful third-party integration, economy transaction, database migration, server-version support, or multiplayer safety is inferred from a compile/build result.

Before a parity release: finish collection/metadata semantics and exact resource compatibility; qualify the integrated virtual-production/stock/purchase/menu/wand flows; label or implement each external adapter; exercise place/stack/break/restart, withdrawals with full inventories, concurrent GUI sessions, failed economy operations, hot reload and chunk unload on Paper and Folia. Validate migration on disposable copies. Keep unavailable integrations explicit in logs and documentation.

Reproduction: use Python `zipfile.ZipFile.namelist()` and SHA-256 per entry for resource/inventory comparison, and `javap -c -p -classpath <working-copy.jar>` for `BuyStackMenu`, `StockMenu`, `StockChest`, and `SpawnerGenerator`. Cite reference class/method symbols as stable evidence anchors; obfuscated members and encrypted strings limit deeper semantic recovery.
