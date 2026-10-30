# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/YanIanZ/BravenSpawner/compare/v1.1.2...HEAD
[1.1.2]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.2
[1.1.1]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.1.1
[1.0.2]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.2
[1.0.1]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.1
[1.0.0]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.0
