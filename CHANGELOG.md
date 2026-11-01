# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 1.3.1-dev

### Fixed
- Production now runs as one delayed-task chain per spawner instead of a repeating
  task that was cancelled and reinstalled after every cycle (TH-04). Each cycle samples
  a fresh interval and speed booster; replaced, unregistered or stopped chains end, and
  skipped or failed cycles keep the chain alive.
- Hopper transfer no longer dispatches a region task every 8 ticks for each stocked
  spawner without a hopper (TH-02). A probe that finds no hopper (or an unloaded chunk)
  backs off for 16 passes (~6.4 s); placing a hopper under a spawner wakes it at once.
- Ninja invisibility only considers spawners within the player's send view distance
  (+1 chunk) through a per-world/per-chunk index, instead of every spawner and an AIR
  packet per distant spawner per player each refresh (TH-02).
- A managed spawner whose block disappeared without a break event (explosion with
  damage enabled, WorldEdit, `/setblock`) pauses production with one warning instead of
  producing for an invisible record. Stock and XP are kept.
- `restore-missing-spawners` logs what it replaced and writes a `RESTORE` audit event.
- Autosell dispatches online owners to their entity scheduler before resolving the
  currency, fetching shop quotes or depositing. One queued sale per spawner prevents
  duplicate work; retired/rejected tasks release their slot. Queued work rechecks
  storage readiness, live spawner/owner identity, player session, toggles and currency.
- Plugin shutdown now stops the existing sell service and invalidates queued sales.
- Folia offline autosell preserves stock until the owner returns; Paper retains
  offline-capable payouts. Third-party provider Folia qualification remains separate.
- Menu actions, storage-cell clicks and corruption puzzle clicks now execute on the
  next player/entity tick rather than inside `InventoryClickEvent`. A queued action
  verifies the original session/window and current access; closing, replacing,
  disconnecting, revoking access or removing the spawner cannot replay that action.
- Resource expansion captures the version during configuration, avoiding Gradle's
  deprecated execution-time `Task.project` access and enabling configuration-cache use.

### Added
- **Island worlds and SuperiorSkyblock2** ([docs](docs/ISLAND_INTEGRATIONS.md)). SourbyCraft
  Aurora World Fabric and AdvancedSlimePaper island worlds: unloading pauses production
  and drops holograms, loading resumes them (also Canvas `WorldUnloadAsyncEvent`).
  SuperiorSkyblock2: island privileges for use/place/break, members sharing island
  spawners, spawner-rates and mob-drops upgrade multipliers, record cleanup on disband
  (including unloaded AWF worlds), and autosell only for current island members, which
  keeps Aurora MultiProfile purses separate. Fails closed when SSB2 is broken.
- **Crash-safe sell settlement (STOR-01, VSS-05).** A sale first moves the priced units
  into an escrow stored inside the spawner record, saves it, and only then asks the
  economy to pay (back on the payee's or the global thread). A confirmed deposit drops
  the escrow; a refusal, a failed save, a player who left or a shutdown before payment
  returns the units. A crash can no longer duplicate value: escrows found after a
  restart have an unknown payment outcome and are held until an administrator runs
  `/bspawner escrow list|refund <id>|confirm <id>` (audited as `ESCROW`). Spawners with
  an open escrow cannot be broken. Sell, autosell and the sell wand report the final
  outcome once the payment settles; the provider call no longer holds the spawner lock.
- **Exact-count virtual storage (STOR-01 local core, VSS-01/03/08/09).** Each stored
  item identity is one entry with an exact 64-bit quantity (`StockEntry`) instead of an
  `ItemStack` with an int amount. Deposits use checked arithmetic and never wrap; the
  BSC2 codec now writes and reads those quantities without the old int clamp.
- `storage-limits.max-distinct-items` and `storage-limits.max-per-item` in `config.yml`
  (0 = no gameplay limit), independent of the product's total `max-stored-items` cap.
  A spawner never holds more than 4096 identities, the most the codec can save.
- Sell settlement, take-all and hopper/GUI returns remove and restore stock by identity
  (`Spawner.take` / `restore`). Take-all moves at most one inventory's worth per click
  instead of draining and re-storing the whole store.
- Per-action menu permissions (PM-01): `bravenspawner.action.sell`, `.xp`, `.upgrade`,
  `.buy`, plus `bravenspawner.action.*`. All default to `true`, so existing servers keep
  their behaviour; negating a node blocks that action for everyone (including admins),
  and never grants access to a spawner. Collect-all performs only the permitted halves.

### Changed
- Storage codec v3 adds an escrow section and is written only while a record holds an
  escrow; every other record stays byte-identical v2. An older release refuses (and
  keeps) a v3 record instead of loading it without its escrowed units.
- Loading merges legacy entries of the same identity into one; the on-disk format is
  unchanged (BSC2 v2), so no schema migration runs. **Downgrade caveat:** 1.3.x and
  older clamp any single identity above 2,147,483,647 when reading, so take a backup
  before downgrading a server that stored more than that of one item.
- The autosell notification uses `messages.yml` (`economy.autosold`).
- Block, stacking, booster-item and repair chat lines moved from hard-coded text to
  `messages.yml` (new `spawner.*`, `booster-item.*` and `repair.*` keys) so the prefix
  and colour roles apply everywhere. Missing keys fall back to the bundled defaults.
- `sounds.yml` `stacking` now also plays when merging a spawner item into a block, and
  `booster.add` plays on booster activation. `booster.remove` stays unused because
  boosters expire lazily without an event.
- Removed unused code: the old aggregate drop sampler in `SpawnerManager` (production
  uses `ProductRoller`), `ProductLoader.firstStringLegacy` and an unused import.
- `StackService.canonicalCount` documentation now matches the code: oversized counts
  are rejected by place/merge, not clamped (EX-10).
- Removed the unused `instant_page_navigation` option from bundled config; old copies
  are ignored. Inventory actions always use the next player tick.
- CI uses configuration cache and fails on Gradle deprecations, limits token permissions
  to read-only contents, cancels superseded runs and retains test reports on failure.
- Synchronized documentation to actual 1.3.0 scope; incorporated the split core specs
  and parked proposals locally. Proposed targets: 1.4.0 vault, 1.5.0 ledger/UX/API.
- Contributor guidance permits guarded reflection only for optional integrations.


## [1.3.0] - 2026-10-30

### Fixed
- Paper 26.x: `checkpoint flush failed ... could not encode 1 item stack(s)`. Newer Paper
  refuses to serialize ItemStacks above 99. Item codec v2 stores the amount separately
  (`BSC2` header + long amount + `serializeAsBytes` template of one item). Legacy blobs,
  including 1.21.1 blobs with amounts above 99, still load. Checkpoint errors now log
  the spawner id and root cause.
- Player-head skins in menus and spawner items: product `head.head-texture` is now parsed
  and `%head_texture%` / `spawnerhead` resolve to it (URL, hash or base64; no Mojang lookup).
- Storage cells show the real stored amount (`gui.storage.amount-lore`, e.g. `Stored: 1,234`).

### Added
- Particles: spawner production, stack, upgrade and booster bursts (`particles.*` in
  config.yml); sell wand ring and trail (`sellwand.yml: particles`).
- Mob catalogue completed up to Minecraft 26.2: 50 new `mobs/*.yml` (90 total), drops
  derived from the vanilla 26.2 loot tables, per-drop sell prices, verified head textures.
  `ender_dragon`, `wither`, `giant` and `illusioner` ship with the new `enabled: false` flag.
- Existing installs initialized from the bundled defaults receive newly bundled mobs on
  the next start; files the owner deleted are not recreated (the `.migrated` marker now
  records the offered ids). Migrated legacy catalogues are left untouched.

- Currency abstraction (`econ/Currency`, `CurrencyRegistry`) with per-action selection
  in `economy.currencies` (`sell`, `buy-stack`, `upgrade`, `spawner-buy`). Providers:
  `VAULT` (default), `PLAYERPOINTS`, `coinsengine:<id>` (CoinsEngine 2.x and
  ExcellentEconomy), `EXP_LEVELS`, `EXP_POINTS` and `ITEM` (`economy.item-currency`).
- `economy.format.<currency>`: symbol, suffix, decimals, compact and display name,
  reusing `money-decimals` compaction; `%currency%` / `%currency_symbol%` in sell,
  buy and upgrade messages; new `gui.buy.currency-unavailable` message.
- Shop pricing for ZShop, ExcellentShop, Nascraft and DonutWorth through their
  public APIs (reflection); `ShopBridge` order and the `sell-prices` fallback are unchanged.
- `/bspawner economy` summary; `/bspawner doctor` lists available currencies and the
  selection per action.

### Changed
- Entity types and drop materials resolve version-safely (registry key first, then the
  enum name, plus aliases such as `MUSHROOM_COW`/`MOOSHROOM`, `SNOWMAN`/`SNOW_GOLEM`).
  Products whose type does not exist on the running server (e.g. `sulfur_cube` on
  1.21.1) are skipped with one info line; unknown drop materials are skipped with one
  summarised warning instead of per-file errors. `mushroom_cow.yml` keeps its id and now
  names `MOOSHROOM`.
- Sale payouts, buy-stack and upgrade charges go through the selected currency.
  Whole-unit currencies round payouts down and charges up; an unavailable or unknown
  currency refuses the action with a message and is logged once.
- `hooks.yml: currency` is now the default for actions without an explicit
  `economy.currencies` entry instead of being ignored.
- Buy/upgrade messages format amounts with the plugin's money format (same as menus)
  instead of the Vault provider's own format.
- Autosell skips offline owners when the sell currency is online-only (XP, items).

### Not implemented
- SuperMobCoins: no public API. Per-product `currency:` overrides: products have no
  generic extras map yet. `spawner-buy` is reserved; no spawner shop flow exists.
  `%currency%` is not added to menu placeholders (formatted prices already carry the symbol).

## [1.2.1] - 2026-10-30

### Fixed
- Startup crash `UnsatisfiedLinkError: ...libs.sqlite.core.NativeDB._open_utf8`: the
  broken jars committed under `artifacts/` (sqlite relocated) were removed;
  `ShadedSqliteTest` opens SQLite through the shipped jar on every build.
- Storage init failure now fails closed (safe mode) instead of silently switching to
  YAML; `/bspawner doctor` works in that mode and shows the cause.
  `databases.yml: fallback-to-yaml` restores the old behaviour with a fresh file.
- Exploits: no vanilla XP on spawner break, `cooldowns.break/stack` enforced,
  plain-material boosters removed (tagged items only, stronger booster kept),
  tier upgrades never slow a spawner, take-all splits by max stack size,
  vanilla conversion respects blacklist/chunk cap, payouts rounded HALF_UP.
- Holograms honour `enabled` and `commands.shtoggle.enabled`; bars use real capacity.
- Every placeholder and `nbt:` action in the bundled menus is wired (incl. Bedrock
  sell/take, right-click take 64 / sell); bundled storage and log menus have content rows.

### Added
- Colours: `&#RRGGBB`, `{#RRGGBB}`, `<#RRGGBB>`, `&x&R&R&G&G&B&B`, MiniMessage tags
  (gradient, rainbow, …); unicode escapes `\uXXXX` / `\U0001F525`; item text non-italic.
- `messages.yml` with a configurable prefix and colour roles; confirmations for
  Sell all / Collect all / Buy max; GUI sounds; owner-visible history.
- SQL schema v2 (`corrupted_at`); dirty-only batched checkpoints.
- `debug` and `ownership.enabled` config keys are now honoured.

### Changed
- Build: Gradle version catalog; sqlite-jdbc 3.54.1.0, HikariCP 7.1.0,
  mysql-connector-j 26.10.0, MongoDB 5.14.0, JUnit 6.1.3, Mockito 5.24.0;
  protobuf relocated; service files merged; signatures stripped.
- Chunk limit counts spawner blocks by default (`spawner-limit-in-chunk-mode: stacks`
  for the old behaviour).

### Removed
- Unused config keys (`item_spawner_optimization`, `use_new_ticking_system`,
  `multiple-drop`, `full-stack`, `disable-item-stock`, `hologram.*`) and orphan classes.

## [1.2.0] - 2026-10-30

### Changed
- `gui/spawner.yml`, `storage.yml`, `buy_stack.yml`, `corruption.yml` and
  `log.yml` are now byte-identical to VirtualSpawner 7.2.0's shipped menus.
- `holograms.yml` is byte-identical to the original, and the renderer reads that
  schema: `hologram.content`, bar length/character/colours, activation radius,
  corrupted palette and text. Placeholders `%stack%`, `%spawner_type_cap%`,
  `%storage_bar%`, `%xp_bar%`, `%color%` and `%corruption_status%` resolve the
  same way the original documents them.

## [1.1.4] - 2026-10-30

### Added
- `/bspawner doctor`: storage backend, load state, live spawner count, catalogue
  size, economy, tier count and one sample product on a single screen.

### Fixed
- Product files written by the original plugin (`type:`, `display:`) were rejected
  as malformed because only `entity-type:` and `display-name:` were read. Both
  spellings now resolve, so a migrated catalogue is not silently emptied.

## [1.1.3] - 2026-10-30

### Fixed
- `UnsatisfiedLinkError: NativeDB._open_utf8` on first SQLite connection. Shadow
  relocated `org.sqlite` while the native `.so` resources kept their original
  path, so the loader looked in a package that no longer existed. The driver is
  no longer relocated; Hikari, MySQL, MongoDB and BSON still are.
- A storage init failure no longer disables the plugin. It logs the cause and
  falls back to YAML for that run, so spawners keep working while the database
  configuration is repaired.

### Added (1.2.0-dev continuation)
- Integrated verified REA reconstruction: typed booster items/effects, native events,
  restriction gates, WorldGuard checks, MongoDB Braven-schema storage and typed
  EssentialsX/ShopGUI+/EconomyShopGUI price adapters.
- Active timed-booster persistence with absolute expiry, five-second async
  checkpoints, shutdown saves and corruption preservation. Personal carried-item
  effects are rebuilt from inventories. Hard crashes may lose changes since the
  last checkpoint; reference database migration is not implemented.
- Integrated configured storage withdrawal/corruption paths and lifecycle,
  sale-settlement and Folia scheduling corrections. See FEATURE_PARITY.md for
  exact coverage and integration-test limitations.

## [1.1.2] - 2026-10-30

### Added
- Startup platform report: server brand, Minecraft version, Java, region-threaded
  detection, Folia API presence and whether sourbyapi/SourbyCraft/AuroraBridge are
  installed. One line, at INFO, before any subsystem starts.

### Changed
- `api-version` raised from 1.20 to 1.21 to match the compiled target.
- `sourbyapi` and `SourbyCraft` added to `softdepend`.

### Fixed
- A failure during enable now logs the full stack trace and a remediation line
  instead of leaving a half-initialised plugin that only reports "plugin is
  disabled" when a command runs.

## [1.1.1] - 2026-10-30

### Fixed
- The sell wand listener was registered twice — once by `SellWandService.install`
  and once by the plugin — so a wand click ran the sale path twice. The plugin no
  longer registers it.

## [1.1.0] - 2026-10-30

### Added
- Menu engine driven by `gui/*.yml`: layout strings, per-character items,
  placeholders, filler and a dedicated Bedrock layout.
- SellWand with radius collection, price modifiers and cooldown feedback.
- Per-product YAML: `mobs/*.yml` and `itemspawners/*.yml`, with automatic
  migration from the legacy `config.yml` product block.
- Item stock and buy-stack limits per player.
- Bedrock detection (Floodgate, then Geyser UUID prefix) and PlaceholderAPI
  resolution for GUI text.
- Config-driven holograms in `holograms.yml` with tier overrides.
- Shop price routing for ShopGUI+, EconomyShopGUI, ZShop, ExcellentShop and
  EssentialsX, plus `multiple-drop`, production budget and block-entity tuner.
- Audit log: async daily files, bounded queue, log menu.
- Buy-stack economics with per-purchase, per-player and cooldown limits.

### Fixed
- Disabling before the first load completed wrote an empty snapshot over the
  durable state; the final flush is now gated on a completed load.
- Sell wand listener was registered with the wrong constructor arity.

### Verified
- 41 unit tests covering stacking, persistence, product compatibility, menu
  routing, purchase maths, wand lifetime and GUI authorisation.

## [1.0.2] - 2026-10-30

### Fixed
- Booster sweep used `Bukkit.getScheduler()`, which does not exist on Folia.
  It now runs on the global region scheduler.
- Shaded libraries were relocated into the pre-rename package because a stale
  incremental artifact survived `clean`; a full rebuild now produces
  `dev.yanianz.bravenspawner.libs.*` only.

## [1.0.1] - 2026-10-30

### Fixed
- GUI lock gates are now enforced on open and released on close: `canOpen`,
  `markOpened` and `markClosed` are called from the inventory path, so the
  reconnect cooldown and the post-close save lock actually apply.
- Page navigation inside one spawner no longer re-triggers the cooldown.

### Changed
- Gradle wrapper committed; CI builds through `./gradlew`.

## [1.0.0] - 2026-10-30

### Added
- Sell service: per-material pricing, multiplier, autosell, notifications switch.
- Shop hook: `hooks.yml` selection with clear logs when a plugin is absent.
- Ninja spawners: per-player block invisibility by distance.
- Hopper integration: virtual storage feeding a hopper beneath the block.
- Mending: stored XP repairs the breaking tool when enabled.
- Restore-missing: spawner records survive world rollbacks.
- Corruption: random countdown, production halt, radius repair.
- GUI guards: reconnect cooldown, post-close lock, teleport auto-close.

## [1.0.0] - 2026-10-30

### Added
- Virtual spawners with per-tier interval and stack batching.
- Storage tiers, unlimited mode, paged chest GUI.
- Ownership, per-spawner trust, `/vmodify`.
- Economy: purchase, upgrades, Vault integration.
- Boosters: player and spawner multipliers.
- Holograms on TextDisplay, no external dependency.
- Persistence: YAML, SQLite (WAL), MySQL — one interface, transactional flush.
- Paper region scheduler throughout; Folia supported.

[Unreleased]: https://github.com/YanIanZ/BravenSpawner/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.2.0
[1.1.4]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.4
[1.1.3]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.3
[1.1.2]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.2
[1.1.1]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.1
[1.0.2]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.2
[1.0.1]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.1
[1.0.0]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.0
