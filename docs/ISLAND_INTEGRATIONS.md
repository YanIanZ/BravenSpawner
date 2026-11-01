# Island worlds and SuperiorSkyblock2

Status: shipped in 1.4.0. Mocked tests only; live qualification is owner case OWN-24 to OWN-26 in [TESTER_OWNER.md](TESTER_OWNER.md).

## Per-island worlds: SourbyCraft Aurora World Fabric (AWF) and AdvancedSlimePaper (ASP)

Both keep each island in its own Bukkit world, load it on demand and unload it when idle (AWF: `ssb_<island uuid hex>_<dimension>` via SSB-AuroraWorlds; ASP: InfernalSuite slime worlds). BravenSpawner follows `WorldLoadEvent`/`WorldUnloadEvent`, plus Canvas's `WorldUnloadAsyncEvent` on SourbyCraft (registered by class name; no sourbyapi compile dependency).

- **Unload:** production leases of that world are cancelled and its holograms dropped. Records, stock, XP and escrows are untouched.
- **Load:** production is re-armed, `restore-missing-spawners` runs for that world when enabled, and holograms are rebuilt.
- Spawners of worlds that are not loaded at boot stay idle until their world loads.
- The startup platform line reports `aurora-world-fabric`, `aurora-multiprofile`, `advanced-slime-paper` and `superiorskyblock2`.

## SuperiorSkyblock2 (`superiorskyblock` in config.yml, default on when SSB2 is installed)

| Feature | Behaviour |
| --- | --- |
| Island privileges | On an island, use/place/break require the configured SSB2 privilege (defaults `CHEST_ACCESS`, `BUILD`, `SPAWNER_BREAK`), in addition to WorldGuard and ownership. SSB2 bypass mode allows. Names starting with `BRAVENSPAWNER_` are registered as new privileges in `onLoad`; built-in names are never registered. |
| Shared spawners | `members-share-spawners`: island **members** holding the use privilege may use every spawner of their island; visitors and coops never share, even with the privilege. Breaking stays with the spawner's owner. |
| Multipliers | The island's `spawner-rates` upgrade shortens the production interval; `mob-drops` multiplies loot. Non-finite or non-positive values count as 1; capped by `max-multiplier`. |
| Disband | `remove-spawners-on-disband`: records inside the island, or in its AWF worlds (even unloaded), are removed and audited as `BREAK`. Spawners with an open sale escrow are kept. |
| Autosell | `autosell-requires-membership`: the owner is paid only while a member of the spawner's island; otherwise the stock stays. A spawner in an unloaded island world waits for the world to load. |
| Failure | SSB2 installed but disabled, or an API error: spawner actions are denied (fail closed) on island worlds only (`island-worlds` prefixes, default `SuperiorWorld`, `ssb_`), logged once. |

## Aurora MultiProfile (SSB-AuroraProfiles)

Profiles swap a player's inventory, purse and island. With SSB2's native profile support, island membership follows the active profile. That gives two guarantees:

- **No GUI action across profiles.** Every action re-checks the island privilege, so a player on another profile is treated as a visitor of the first profile's island.
- **No autosell into the wrong profile.** A spawner on profile A's island never pays into profile B's purse; its stock waits until A is active again.

Not handled:
- MultiProfile has no switch event, so a payment that lands exactly between AWF's purse capture and apply is not guarded.
- Without SSB2, BravenSpawner cannot map a spawner to a profile.
