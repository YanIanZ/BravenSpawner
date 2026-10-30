# Typed price and protection integrations

`ShopBridge(BravenSpawner)` reads `hooks.yml` and selects only its `shop` value.
`None` disables provider pricing. Both price overloads quote one item while
retaining its metadata; the player overload lets a provider apply player pricing.
Providers never deposit money or remove virtual stock. SellService remains the
single Vault settlement path and controls its own configured multipliers.

## API evidence

| Provider | Compile-only API | Calls |
| --- | --- | --- |
| EssentialsX | `net.essentialsx:EssentialsX:2.21.2` | Installed `IEssentials.getWorth().getPrice(essentials, unit)` |
| ShopGUI+ | `com.github.brcdev-minecraft:shopgui-api:3.2.0` | `ShopGuiPlusApi.getItemStackPriceSell(unit)` or player overload; checks `areShopsLoaded()` |
| EconomyShopGUI | `com.github.Gypopo:EconomyShopGUI-API:1.11.0` | `getShopItem`, `isSellAble`, `getItemSellPrice`; player-aware overload when supplied |

The supplied reference JAR's `EssentialsXBridge.getSellPrice`,
`ShopGuiPlusBridge.getSellPrice` and `EconomyShopGUIBridge.getSellPrice` were
inspected using `javap -c -p`, without executing the plugin. They confirm these
families of direct API calls. No reference bytecode or API classes were copied.

Sources: [EssentialsX Worth source](https://github.com/EssentialsX/Essentials/blob/2.x/Essentials/src/main/java/com/earth2me/essentials/Worth.java),
[EssentialsX release repository](https://repo.essentialsx.net/releases/net/essentialsx/EssentialsX/maven-metadata.xml),
[ShopGUI+ API and compatibility matrix](https://github.com/brcdev-minecraft/shopgui-api),
[EconomyShopGUI API](https://github.com/Gypopo/EconomyShopGUI-API),
[EconomyShopGUI API usage](https://wiki.gpplugins.com/economyshopgui/api/api-usage).
ShopGUI+ API 3.2.0 documents ShopGUI+ 1.111.0 or newer. EssentialsX uses the Bukkit
name `Essentials`; `EssentialsX` is accepted as an alternate name. Both free and
premium EconomyShopGUI names are supported.

## Limits

These are unit price integrations, not replicas of provider transactions.
EconomyShopGUI items using non-Vault currencies, multiple prices or limited sell
stock are refused by its adapter. Dynamic market transaction bookkeeping,
quantity-dependent prices and shop-specific sale events are not implemented.
ShopGUI+ deployments must use a currency consistent with Vault settlement.
No-player quotes do not include personal shop permissions or price modifiers.
Other provider adapters remain unavailable. Missing/unready APIs, invalid quotes
and unsupported items return no provider price; callers use their configured
fallback. No runtime provider, payout or Folia compatibility has been qualified.

## WorldGuard

`ProtectionService` calls WorldGuard 7's region query for build plus action flags,
and honors its session bypass. `canPlace` checks block placement, `canBreak`
checks block breaking and `canUse` checks interaction/container access. Root
listeners and radius actions must apply the appropriate method to every target
while continuing to honor Bukkit event cancellation and model ownership.
WorldGuard absence allows the action; a present but disabled/broken API denies it.
Optional API types are isolated in a nested class.

The official compile coordinates are
`com.sk89q.worldguard:worldguard-bukkit:7.0.12` (including its core dependency) and
`com.sk89q.worldedit:worldedit-bukkit:7.3.9`, from
`https://maven.enginehub.org/repo/`. EngineHub returned HTTP 403 during inspection.
A genuine fallback distribution was obtained from the maintainer-owned
[WorldGuard Modrinth release](https://modrinth.com/plugin/worldguard/version/J66QOTLZ),
whose project links to EngineHub's source repository and lists maintainers
`me4502` and `wizjany`. Modrinth also publishes it as
`maven.modrinth:DKY9btbd:J66QOTLZ` at `https://api.modrinth.com/maven`.
The downloaded `worldguard-bukkit-7.0.12-dist.jar` contains the real API/core;
its 1,163,769 bytes match Modrinth's published SHA-512. Its SHA-256 is
`48b136938b3cd4816a95887847ff28e56a06fb110103344bb574739c35f9e86c`.
The local API evidence directory contains the artifact and a JSON provenance
receipt. No API classes were fabricated. API calls were inspected with `javap`;
root build validation remains separate.
Sources: [official dependency documentation](https://worldguard.enginehub.org/en/latest/developer/dependency/),
[RegionQuery source](https://github.com/EngineHub/WorldGuard/blob/version/7.0.x/worldguard-core/src/main/java/com/sk89q/worldguard/protection/regions/RegionQuery.java).
