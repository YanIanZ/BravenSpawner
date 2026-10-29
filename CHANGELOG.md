# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/YanIanZ/BravenSpawner/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.1
[1.0.0]: https://github.com/YanIanZ/BravenSpawner/releases/tag/v1.0.0
