# Gap Audit — 2026-10-30

> **Status update (2026-11-01):** Historical v1.2.0 audit. The in-flight worktree note and release targets below are archival; the active worktree and [roadmap](ROADMAP.md) supersede them. TH-03 is addressed in the 1.3.1-dev working tree; see [current review](CODE_REVIEW.md).

Read-only static review of `main` @ `7ed949e` (version `1.2.0`, Java 21, Paper API 1.21.1). This audit did not run Gradle or a server. Every finding below was checked against the cited source line. Line numbers are from `7ed949e`; the two newest commits (`b6922f8`, `7ed949e`) do not touch any cited file.

Severity: **P0** means loss or duplication of assets or data, or a silent integrity break. **P1** means a player-visible broken feature, an exploit, a Folia ownership risk, or money charged for no effect. **P2** means a correctness, UX or maintenance issue.

> **In flight:** worktree `/Volumes/Ext/REA/BravemSpawner-text` (`feat/text-hex-unicode`) has uncommitted edits to `util/Text.java`. It probably addresses UI-01 (hex colours). Coordinate before touching `Text`.

---

## 1. Correctness and robustness

### 1.1 Storage durability

| ID | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| ST-01 | **P0** | `BravenSpawner.java:148-159` | When the SQLite, MySQL or Mongo `init()` fails, the plugin silently switches to `YamlStorage` "for this run". A leftover `spawners.yml` from an earlier YAML period then loads stale contents and XP, which can be withdrawn or sold a second time (a dupe). If no such file exists, every DB spawner becomes an unmanaged block. Spawners placed during the fallback exist only in YAML and are orphaned after the DB is fixed. | Fail closed: stay not-ready, or go read-only with commands that explain why. At minimum, never load a YAML file that pre-dates the fallback, and record the fallback in `/bspawner doctor`. |
| ST-02 | P1 | `SqlStorage.java:343-381` | Every checkpoint UPSERTs **all** live spawners, re-encoding every inventory, one prepared statement per row with no batching. The `dirty` set only gates whether a flush happens. The class doc ("incremental UPSERT") is misleading. | Write only `pending ∩ live`, use `addBatch`, and keep full rewrites for `flushNow`. |
| ST-03 | P2 | `YamlStorage.java:116`, `SqlStorage.java:315` vs `SpawnerDocumentCodec.java:27,48` | YAML and SQL rebuild corruption with `corrupt()` (timestamp = load time) and lose `corruptedAt`. Only Mongo persists it. | Add a `corrupted_at` column/key and call `restoreCorruption`. |
| ST-04 | P2 | `Storage.java:146-152` | `encodeTrusted` iterates a `synchronizedSet` without holding its monitor. A checkpoint racing `/bmodify` can throw CME, which fails the whole flush (it is retried later). | Copy the set under `synchronized (trusted)`. |
| ST-05 | P2 | `listener/EntityListener.java:36-82` | The legacy tagged-mob path stores drops and XP but never calls `markDirty`, so the changes are not persisted. It also uses the tier `capacity` model that virtual storage no longer uses. | Remove the dead path or mark it dirty. |

### 1.2 Threading (Paper and Folia)

| ID | Sev | Evidence | Finding |
|---|---|---|---|
| TH-01 | P1 | `SellService.java:273-326, 399` | The autosell pass runs on the **global region** thread for every spawner. It calls `ShopBridge.priceOf` (ShopGUI+, EconomyShopGUI and EssentialsX APIs) and Vault `depositPlayer`. Those providers are not documented as Folia/global-thread safe, and the whole pass holds one `saleLock`. Paper is unaffected; Folia is a risk. |
| TH-02 | P2 | `HopperLink.java:58-68`, `Ninja.java` `refresh` | Every 8 ticks the hopper code schedules one region task per spawner that has stock, even when no hopper exists. Ninja runs players × spawners every second. The work is O(N) with no chunk index. |
| TH-03 | P2 | `SpawnerGui.java:507-513, 626` | New inventories are opened from inside `InventoryClickEvent`, with no next-tick hop. Bukkit discourages this; it can cause cursor or ghost-item desync. `instant_page_navigation` is parsed but unused (see CF-01). |
| TH-04 | P2 | `SpawnerManager.java:263-265` | `produce()` cancels and reinstalls its own repeating task every cycle (`reschedule`) to resample the delay. It is correct but churns the scheduler. A `runDelayed` chain would be cheaper. |

### 1.3 Economy and exploits

| ID | Sev | Evidence | Finding |
|---|---|---|---|
| EX-01 | P1 | `BlockListener.java:168-239` | Breaking a spawner never calls `event.setExpToDrop(0)`. Vanilla spawner XP (15–43 per break without Silk Touch) can be farmed by repeatedly placing and breaking. `cooldowns.break` is parsed (`Settings.java:172`) but never enforced. |
| EX-02 | P1 | `SpawnerGui.java:714-721` | "Take all" passes logical stacks (amounts up to `Integer.MAX_VALUE`, e.g. 500 ender pearls) straight into `PlayerInventory.addItem`. Whether CraftBukkit splits oversized stacks by the **item's** max stack size (rather than the inventory max of 99) is not verified on 1.21.1, so overstacked pearls, tools or potions are possible. The storage-cell path already clamps (`:615-616`). | Split by `getMaxStackSize()` before `addItem` and add a test. |
| EX-03 | P1 | `config.yml:116-119`, `BlockListener.java:262-270`, `BoosterState.java:58-69` | Booster items are default vanilla materials (`SUGAR`, `BLAZE_POWDER`, `NETHER_STAR`) matched by `Material` only, so any sugar grants a 1.5× XP/loot boost for 10 minutes. `grant()` also uses `put`, so a weaker item **replaces** a stronger active booster. | Require PDC booster items (typed system) and keep the max multiplier. |
| EX-04 | P1 | `SpawnerManager.java:203-207`, `ProductLoader.java:256-260` | Tier upgrades replace the product delay with `tier.intervalTicks`. A reference-format mob (`spawn-delays` 5–15 s, i.e. 100–300 ticks) upgraded to `iron` (900 ticks) becomes 3–9× **slower** after paying 5,000. Tier `capacity` has no effect on virtual storage, which uses `maximumItems` from the product. | Use `min(product, tier)` or a tier multiplier, and make capacity scale `maximumItems`. |
| EX-05 | P2 | `SellService.java:233`, `SpawnerGui.java:735` | Trusted players can sell the owner's storage, and the payout goes to the **clicker**. This is a policy decision; document it or add a `pay-owner` option. |
| EX-06 | P2 | `SellService.java:421`, `SpawnerGui.java:486` | Sale value is an unrounded `double`. The reference rounds HALF_UP to whole currency (FEATURE_PARITY §Native event). `total_price` lore is `price*64` even when the room is less than 64 (`:250`). |
| EX-07 | P2 | `Settings.java:263-273` | Tier `cost`, `interval-ticks` and `capacity` are not validated. A negative cost gives a free upgrade (`Economy.withdraw` returns true for amounts ≤ 0, `Economy.java:32`). |
| EX-08 | P2 | `BlockListener.java:151-165` | `convertVanilla` skips the chunk cap and blacklist checks and defaults unknown types to ZOMBIE. |
| EX-09 | P2 | `config.yml:29,40`, `SpawnerManager.java:101-111` | `spawner-limit-in-chunk: 100` counts **stack units**, so with `stack-limit: 200000` no stack above 100 can be placed or merged. The defaults contradict each other, and every cap check does a full-map scan. |
| EX-10 | P2 | `StackService.java:150-168` | The doc says `canonicalCount` clamps to `max`; it does not. Place and merge reject oversized counts, so there is no exploit, but the code and docs disagree. PDC counts are unauthenticated (see the PR #7 ledger discussion). |

### 1.4 Permissions and commands

| ID | Sev | Evidence | Finding |
|---|---|---|---|
| PM-01 | P2 | `plugin.yml`, `SpawnerGui.java` | `bravenspawner.use` is only a command gate. There are no nodes for sell, upgrade, buy-stack or collect-xp. Only `item.extract` is checked (`:607, :710`). |
| PM-02 | P2 | `CommandHandler.java:102-105` | When `reload` hits a failed pre-flush, `reloadAll()` throws an uncaught `IllegalStateException`, which shows as "internal error". |
| PM-03 | P2 | `CommandHandler.java:125-129` | `menu`, `corrupt` and `repair` act on the *first* owned spawner from an unordered map (named `nearest`). They should target the block the player is looking at. `doctor` is missing from help and tab completion (`:92, :433`). |
| PM-04 | P2 | `CommandHandler.java:406-424` | `/bhtoggle` ignores `holograms.yml: commands.shtoggle.enabled` (`HologramConfig.toggleCommandEnabled()` is never read). |
| PM-05 | P2 | `CommandHandler.java:152-158, 226-231`, `Settings.java:379-390` | Invalid numbers (give stack, booster seconds, drop amount/chance) silently fall back to defaults with no warning. |

### 1.5 Config keys parsed but not used, or never read

| Key (`src/main/resources`) | State | Evidence |
|---|---|---|
| `item_spawner_optimization`, `use_new_ticking_system`, `instant_page_navigation`, `multiple-drop`, `full-stack`, `disable-item-stock`, `ownership.enabled`, `cooldowns.break`, `cooldowns.stack` | Parsed into `Settings`, **zero consumers** | `Settings.java:163-216` (also `ownership.enabled` defaults to `false` in code but `true` in the yml) |
| `hologram.enabled`, `hologram.line`, `debug` (config.yml) | Never read | grep: no consumer. Holograms use `holograms.yml`. |
| `holograms.yml: enabled` | Read, **ignored**. Holograms always spawn. | `HologramConfig.java:80` vs `Holograms.update` (`:39-88`, no `enabled()` check) |
| `money-decimals.formats.*` | Read correctly by `Settings` (`:234`) but the money text uses the wrong path `money-decimals.<n>`, so custom formats are ignored | `SellService.java:252`, `BuyStackService.java:223` |
| `sounds.yml: ui.click, stock.withdraw, stacking, booster.add/remove, sell.stock` | Never played. Only the three sell-wand cues are wired. | `SoundFeedback.java:54-56` |
| `databases.yml: mysql.max-pool` | Never read (code reads `pool-size`) | `SqlStorage.java:93` |
| `gui/storage.yml: informative_lore, max_page_amount, selected_text`; `gui/spawner.yml: special.booster_display, allow_sell_with_storage` | Never read | grep: no consumer |
| Orphan classes (about 700 lines) | Not referenced | `optimise/ItemSpawnerTuner`, `optimise/MultiDrop`, `optimise/ProductionBudget`, `stock/BuyStackService`, `product/StockService`, `platform/PlatformText` |

### 1.6 Other robustness issues

- **P2**: `HologramConfig.java:153-163` computes the storage bar as `stored/(distinctSlots*64)` and the XP bar against a hard-coded 100,000. It ignores `maximumItems` and `maximumXp`, so the bars are meaningless.
- **P2**: `RestoreMissing.java:57-63` overwrites whatever block is now at the location (opt-in), with no audit record. When explosion protection is off, or after WorldEdit or `/setblock`, the record keeps producing for a block that no longer exists (no unregister path).
- **P2**: `CommandHandler.java:182` throws if `tiers` is empty. Settings prevents that today, but the dependency is fragile.
- **Verified OK** (no finding): GUI click and drag handling. Both listeners cancel before any identity check, including shift-click, number keys, double-click collect and drag (`SpawnerGui.java:551-561, 636-640`; `MenuListener.java:49-94`). Holder generation identity, drain-then-insert with leftover return for cells and hoppers, the sale state machine (`SellService` QUOTED→PAID→CLEARED), checking Vault `EconomyResponse`, the YAML atomic replace with quarantine, and the Mongo load gate.

---

## 2. Feature parity gaps vs `ref/` (verified)

| Area | `ref/` keys or features | Local status |
|---|---|---|
| Currency / hooks | `hooks.yml: currency: SuperMobCoins`, `superiorskyblock`, `hooks: []` | Only Vault. No SuperiorSkyblock (roles or multipliers), no `hooks` list. `hooks.yml` is generated by `ShopHook`, not bundled. |
| Shops | ZShop, ExcellentShop, DonutWorth, Nascraft | ZShop and ExcellentShop are 20-line stubs; DonutWorth and Nascraft are detection only. |
| Holograms | `type: decentholograms/fancyholograms/cmi`, `enabled`, `commands.shtoggle.enabled`, hex `#RRGGBB` content | Native TextDisplay only. `enabled` and the toggle flag are ignored, and hex is not parsed (UI-01). |
| Sell wand | `sellwand.yml: particles.*` (type/color/density/gradual), `messages.given/invalid-duration/player-not-found/time-remaining` | No particles. Those messages are hard-coded English in `CommandHandler`. |
| Modifiers | `modifiers.yml: messages.modifier_applied/failed`, `wand_lore.modifier_section.*` | Missing. |
| Product schema | `drops.N.priority`, `itemsadder-name`, `entity-name`, `item-name`, `visual.color`, `head.head-texture` (item spawners) | Not parsed by `ProductLoader` (display-name and CMD per drop are parsed). |
| Menus | `storage.yml` take one/stack (bedrock), drop all, right-click 64, informative lore, `max_page_amount`; `spawner.yml` booster list, right-click sell on storage, bedrock sell-all | See UI-03/UI-04: actions not wired. |
| Placeholders | `other/internal_placeholders.yml`, `%virtualspawner_*%` expansion | Not bundled; no PAPI expansion registered. |
| Integrations | Lands, Towny (declared softdepend), WildLoaders | Only WorldGuard is implemented (`ProtectionService`). |
| API / events | `net.codava.virtualspawner.api`, cancellable place/break/stack/generate/sell events, Currency/Shop registration events | Not present. |
| Versions | 1.16.5 – 1.21 + v26 | 1.21.1 only. TextDisplay requires 1.19.4+. |
| Persistence | Reference DB migration | Not provided (`ProductMigration` handles local configs only). |

FEATURE_PARITY.md is still accurate for these items. It does **not** list the menu-action and placeholder gaps in §3 below, or the hologram `enabled`/hex regression introduced by `261552f` ("ship menus and hologram schema verbatim").

---

## 3. UI / UX gaps

| ID | Sev | Evidence | Gap |
|---|---|---|---|
| UI-01 | P1 | `util/Text.java:13`, `holograms.yml` | `legacyAmpersand()` has no hex support, so `#c2c2c2(...` from the shipped hologram content renders literally above **every** spawner (UI-02). The same problem appears in the `%color%` palette (`HologramConfig.java:148`). (WIP branch noted above.) |
| UI-02 | P1 | `Holograms.java:39` | Holograms show even though `holograms.yml: enabled: false` is the shipped default. |
| UI-03 | P1 | `SpawnerGui.java:235-256` vs `gui/*.yml` | Unresolved placeholders render raw in the shipped menus: `%speed% %drop_info% %xp_info% %displayname% %color% %storage_value% %storage_price% %xp_value% %spawner_price% %booster_name% %booster_multiplier% %remaining_time%`. |
| UI-04 | P1 | `SpawnerGui.java:505-528` vs `nbt:` values | Unhandled actions: `bedrock` (Bedrock Sell All, spawner.yml:72), `takeone`/`takestack` (Bedrock storage layout), `dropall`, `collect_all`, `buystack` (code expects `buy_stack`). Bedrock players cannot sell, take one, or take a stack. |
| UI-05 | P1 | `SpawnerGui.java:598-599`, `storage.yml:30-35` | Storage cells accept only LEFT and SHIFT_LEFT, while the shipped lore and the reference use **right click = 64**. Informative lore is never appended. The `storage` button ignores right-click = sell (`spawner.yml` lore). Actions do not know the click type (`MenuHolder.action(slot)`). |
| UI-06 | P2 | `SpawnerGui.java:747` | Sell All from the storage view sends the player back to the main menu, and buying a stack reopens buy-stack (fine). Navigation is inconsistent; the player should stay on the current view. |
| UI-07 | P2 | `SpawnerGui.java:151-152`, `GuiLock.java:103-107` | `canOpen` already sends the denial message and the caller sends it again, so players see it twice. |
| UI-08 | P2 | — | Nothing asks for confirmation before **Sell all**, **Buy max** (can spend a whole balance) or Take all. There are no sounds for GUI clicks, withdraws, stacking or boosters, even though `sounds.yml` defines them. |
| UI-09 | P2 | all `Text.of("&…")` call sites (~120) | All player messages are hard-coded English with no prefix and no `messages` file. Colour roles drift (errors `&c`, info `&7`, and `&e` both as info and as usage). This prevents localisation and goes against the GUI_UX spec in PR #7. |
| UI-10 | P2 | `SpawnerGui.java:159-176, 204-218` | The fallback (non-YAML) menus have no product name or icon, the storage fallback does not support withdrawing single cells, and the "Trusted" button only prints a command hint. |
| UI-11 | P2 | `SpawnerGui.java:332-377` | The log menu is admin-only. Owners cannot see their own spawner's history, and timestamps are raw `Instant` ISO strings. |
| UI-12 | P2 | `GuiLock.java` | Every close imposes a 3 s reopen cooldown and a 5 s "saving" lock (reference parity). The message says "still saving", which is misleading for in-memory state. Consider shortening the defaults or rewording. |

---

## 4. Review of PR #7 (`docs/virtual-storage-and-stacking-v2-20261030`)

**What it is:** a docs-only PR (+1,389, −0, 20 files, 5 commits). It adds 13 specs under `docs/features/` and appends sections to README, PRD, TECHNICAL_SPEC, ARCHITECTURE, ROADMAP, TESTER_OWNER and docs/README.

### 4.1 Proposed v2 workstreams

| ID | Spec | Core idea | Feasibility against current code |
|---|---|---|---|
| FOUND-01/02 | (AUDIT/ROADMAP) | P0 fixes, Folia contract | **Mostly done on main**: single schedule (`SpawnerManager:139-195`), Vault results, checkpoints, SQL UPSERT, typed holders. The PR still labels these "v1.0.2 / v1.1" gates against v1.0.1. |
| CORE-01 | PRODUCTION_ENGINE | Aggregated production, budgeted real mobs, offline catch-up | Aggregation is already done (closed-form `ProductRoller`). Offline catch-up and the real-mob mode are new. A medium-sized job. |
| STOR-01 | VIRTUAL_STORAGE | `ItemKey → long` vault, capacity semantics, reserve→insert→commit withdrawals | **Feasible next.** `Spawner.storeVirtual` already keeps one logical entry per identity but uses `int` and `ItemStack` (`Spawner.java:270-296`). It needs a vault type, a codec v2 with migration (VSS-09), and key-based reservations in `SellService.drain`, `HopperLink` and the GUI. |
| STAC-01 | VIRTUAL_STACKING | Producer units, an issued-bundle ledger against PDC replay, split/merge | Feasible once STOR-01 lands. Today the count is in unauthenticated PDC (`StackService:150-168`). The ledger needs a new table/collection in all four backends. |
| UPGR-01 | UPGRADE_SYSTEM | Multi-path upgrades | Blocked on fixing EX-04 (tiers currently override product delays). |
| UX-01 / API-01 | GUI_UX / API_SPEC | Confirmations, accessibility, Bedrock, messages, public snapshots and events | Directly addresses UI-03..UI-09 and the missing API parity row. |
| EVOL, NET (Braven Core), AUTO, PROG, INSI, ECON | 6 specs | Genome, fusion, network vault, rule engine, research, analytics | Large new product surface with no current code. Correctly marked "gated"; not next-update material. |

### 4.2 Conflicts and staleness against main

- `git merge-tree` merges **cleanly** (no textual conflicts). The merge base is `0cc497d`, 13 commits behind `main`. Of the files it edits, only `README.md` changed on main (+13/−11, new status block).
- Semantically stale: the PR's README, ROADMAP, ARCHITECTURE and TESTER_OWNER additions assume baseline **v1.0.1 with open P0s** (`FEATURE_CATALOG §5 "Shipped baseline (v1.0.1 audit only)"`, ROADMAP "v1.0.2: fix existing metadata count-on-placement…"). Main is `1.2.0`, and FEATURE_PARITY, CHANGELOG and README already report those fixes. Merged as-is, the PR would reintroduce contradictory status claims next to the updated README banner.
- The release numbering clashes: the PR's "v1.2 = virtual vault MVP" is already used by the shipped `1.2.0`.
- No current-state section in the PR references the existing `storeVirtual`, `ProductRoller`, `StackService` or `SellService` reservation design that STOR-01 and STAC-01 must migrate from.

### 4.3 Recommendation: rebase and split; do not merge as-is

1. **PR 7a (merge soon):** `VIRTUAL_STORAGE.md`, `VIRTUAL_STACKING.md`, `PRODUCTION_ENGINE.md`, `GUI_UX.md`, `API_SPEC.md`. Rebase onto main and add a "Current implementation (1.2.0)" section to each, pointing at the classes above. Renumber targets to 1.3 / 1.4 / 2.0.
2. **PR 7b (park):** EVOLUTION, BRAVEN_CORE, AUTOMATION, PROGRESSION, INSIGHT, BALANCING, moved to `docs/features/proposals/` with a backlog banner. Merge whenever the owner likes; it is not on the critical path.
3. **Drop** the README, ROADMAP and ARCHITECTURE appends. Instead, refresh `ROADMAP.md` and `AUDIT.md` (both still say baseline v1.0.1 @ `90b39fce`) in a separate status PR that links this audit.

### 4.4 Plan for the next update (dependency order)

```
M1 1.2.1 correctness ──► M2 1.3.0 virtual vault (STOR-01) ──► M3 1.4.0 stacking ledger + UX/API base
        │                                                         ▲
        └──► (parallel) Folia/Paper live qualification ───────────┘   then UPGR-01 → v2 gated modules
```

**M1: 1.2.1 "shipped config actually works"** (no schema change; about 1–2 PRs)
- UI-01/UI-02: hex text (finish `feat/text-hex-unicode`) and honour `holograms.yml enabled`/`shtoggle`. Fix the storage and XP bar maths.
- UI-03/UI-04/UI-05: placeholder provider for all menu tokens; wire `bedrock`, `takeone`, `takestack`, `dropall`, `collect_all`, `buystack`; click-type-aware actions (right = 64, right on storage = sell); informative lore.
- EX-01 (zero XP on break plus break cooldown), EX-02 (split by max stack), EX-03 (PDC-only boosters, keep max), EX-04 (tier = `min`/multiplier, capacity scales `maximumItems`).
- ST-01: fail-closed storage fallback. CF fixes: money format path, remove or implement the dead keys from §1.5, delete the orphan classes.
- Tests: menu-token coverage test (every `%token%` in `gui/*.yml` resolves), action coverage test (every `nbt:` maps to a handler), break-XP test, `addItem` split test.

**M2: 1.3.0 Virtual Vault MVP (STOR-01)**
- `VirtualVault` (`ItemKey → long`, checked arithmetic, `max_total/max_distinct`) behind the existing `Spawner` storage API. Codec v2 with a one-way migration from `List<ItemStack>` plus a backup (VSS-09).
- Key-based reservations in `SellService.drain`, `HopperLink`, wand and GUI. Withdrawals are bounded per action.
- Storage: dirty-only batched SQL writes (ST-02), `corrupted_at` (ST-03), trusted-set copy (ST-04).
- Acceptance: VSS-01/03/04/08/09 from the PR spec.

**M3: 1.4.0 Stacking ledger and UX/API foundation**
- STAC-01: issued-bundle ledger (one table/collection per backend) and an idempotent op-id for give, place, merge, break and split. Migration policy for legacy PDC items.
- UX-01 subset: `messages.yml` with a prefix and colour roles; wire the `sounds.yml` cues; confirmation views for Sell all and Buy max; per-action permission nodes (PM-01); owner-visible log.
- API-01 subset: read-only `SpawnerSnapshot` service, cancellable `BravenSpawnerSellEvent`/`StackEvent`, and a `%bravenspawner_*%` PAPI expansion.

Everything from UPGR-01 onward, plus the six parked specs, comes after M3 and after live Paper and Folia qualification.
