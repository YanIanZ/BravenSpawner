# Price, currency and protection integrations

`ShopBridge(BravenSpawner)` reads `hooks.yml` and selects only its `shop` value.
`None` disables provider pricing. Both price overloads quote one item while
retaining its metadata; the player overload lets a provider apply player pricing.
Providers never deposit money or remove virtual stock. SellService remains the
single settlement path — it pays in `economy.currencies.sell` (Vault by default)
and controls its own configured multipliers.

## API evidence

| Provider | Compile-only API | Calls |
| --- | --- | --- |
| EssentialsX | `net.essentialsx:EssentialsX:2.21.2` | Installed `IEssentials.getWorth().getPrice(essentials, unit)` |
| ShopGUI+ | `com.github.brcdev-minecraft:shopgui-api:3.2.0` | `ShopGuiPlusApi.getItemStackPriceSell(unit)` or player overload; checks `areShopsLoaded()` |
| EconomyShopGUI | `com.github.Gypopo:EconomyShopGUI-API:1.11.0` | `getShopItem`, `isSellAble`, `getItemSellPrice`; player-aware overload when supplied |

### Reflective shop adapters

These shops publish no artifact on a public Maven repository this build uses, so
their API is reached by reflection through the shop's own class loader. Any
missing class/method makes the adapter unavailable (logged once) and the local
`sell-prices` apply.

| Provider | API evidence | Calls |
| --- | --- | --- |
| ZShop | [Maxlego08/zShop-API](https://github.com/Maxlego08/zShop-API) source + reference `ZShopBridge` | `ShopPlugin#getShopManager()`, `getItemButton(Player, ItemStack)` or `getItemButton(Material)` → `Optional<ItemButton>`, `getSellPrice(Player,1)` / `getSellPrice(1)` / `getSellPrice()` |
| ExcellentShop | [nulli0n/ExcellentShop-spigot](https://github.com/nulli0n/ExcellentShop-spigot) source (`su.nightexpress.excellentshop`) + reference `ExcellentShopBridge` (`su.nightexpress.nexshop`) | static `ShopAPI.getVirtualShop()`, `getBestProductFor(ItemStack, TradeType.SELL[, Player])`, `Product#getFinalPrice(TradeType, Player)` or `getPrice(TradeType)`, divided by `getUnitSize()` |
| Nascraft | [Bounser/Nascraft](https://github.com/Bounser/Nascraft) source + reference `NascraftBridge` | `MarketManager` static `instance` (checked non-null; `getInstance()` would build a new market), `getItem(ItemStack)`, `Item#getPrice()`, `Price#getSellPrice()` |
| DonutWorth | closed source; reference `DonutWorthBridge` bytecode only | static `me.serbob.donutworth.api.util.Prices.getPrice(ItemStack)` |

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
Shop quotes are plain numbers: a shop priced in one currency while
`economy.currencies.sell` pays in another is a configuration choice the plugin
cannot detect (ExcellentShop/Nascraft products with their own currencies included).
No-player quotes do not include personal shop permissions or price modifiers.
Reflective adapters are verified against public source or reference bytecode only,
never against a running shop. Missing/unready APIs, invalid quotes
and unsupported items return no provider price; callers use their configured
fallback. No runtime provider, payout or Folia compatibility has been qualified.

## Currencies

`econ/CurrencyRegistry` resolves `economy.currencies.<sell|buy-stack|upgrade|spawner-buy>`
(fallback `hooks.yml: currency`, then `VAULT`). Lookup is case-insensitive and
re-checks availability on every call; plugin enable/disable and service
registration events re-announce selections that became usable. An unknown id,
an unavailable provider or `economy.enabled: false` refuses the action.

| Id | Access | API evidence |
| --- | --- | --- |
| `VAULT` | typed (`vault-api` compile-only) | existing `Economy` bridge; checks `EconomyResponse.transactionSuccess()` |
| `PLAYERPOINTS` | reflection | [PlayerPointsAPI source](https://github.com/Rosewood-Development/PlayerPoints/blob/master/src/main/java/org/black_ixx/playerpoints/PlayerPointsAPI.java): plugin `getAPI()`, `look(UUID)`, `give(UUID,int)`, `take(UUID,int)` |
| `coinsengine:<id>` | reflection | [ExcellentEconomyAPI](https://github.com/nulli0n/ExcellentEconomy) Bukkit service: `getCurrency(String)`, `getBalance/deposit/withdraw(Player, String[, double])`, `canPerformOperations()`; legacy [CoinsEngineAPI](https://github.com/FreshSMP/CoinsEngine-folia) statics `getCurrency(String)`, `getBalance/addBalance/removeBalance(UUID, Currency[, double])` |
| `EXP_LEVELS`, `EXP_POINTS` | built in | vanilla XP curve (`ExpMath`); only on the thread owning the player |
| `ITEM` | built in | `economy.item-currency` material/name/custom-model-data |

SuperMobCoins (the reference plugin's second currency) is not implemented: it is a
paid plugin without a published API, and the reference JAR contains no
SuperMobCoins call site to verify against. Selecting it logs the reason and
refuses the action.

Withdrawals in the GUI are refunded through the same currency when the stack
cannot be added; a failed refund is logged as severe. A failed sale deposit
leaves stock untouched (QUOTED → FAILED); a paid sale whose drain fails stays
quarantined until stock returns and is never paid twice.

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
