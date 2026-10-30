# GUI, Commands, Player Accessibility and Administration — UX-01

**Status: PROPOSED** (partly addressed in 1.2.1). Target: **1.4.0 UX base** (per-action permissions, target-block commands, admin inspect, owner log polish); vault views follow the [1.3.0 vault](VIRTUAL_STORAGE.md). Upgrade, automation, network, research and insight screens depend on [proposals](proposals/) and are not scheduled. Vanilla inventory menus are presentation; server services remain authoritative.

## 0. Current implementation (1.2.1)

- **Menu engine:** `menu/MenuService` + `MenuLoader`/`MenuDefinition` load the YAML menus in `gui/` (`spawner`, `storage`, `buy_stack`, `corruption`, `log`; byte-compatible with VirtualSpawner 7.2.0). Views use a typed `MenuHolder` session with a generation counter (`MenuSession`); `GuiGuardListener` and `GuiLock` cover click/drag/close and the reopen cooldown.
- **Actions:** every `nbt:` value maps to the `gui/GuiAction` enum (24 actions incl. `takeone`, `takestack`, `dropall`, `collect_all`, `bedrock`, `buy_1/64/max`, `log*`, `boosters`), dispatched by an exhaustive switch; a test asserts that every bundled `nbt:` value parses and every `%token%` resolves (`gui/MenuPlaceholders`). Right-click takes 64 / sells from storage.
- **Safety and feedback:** Sell all, Collect all and Buy max need a second click within 3 s (`SpawnerGui.CONFIRM_MS`); menus play configured sounds; owners can open their spawner's history (`audit/AuditMenu`).
- **Text:** `messages.yml` holds every chat line with a configurable `prefix` and colour roles (`{success}`, `{error}`, …); `util/Text` accepts legacy `&` codes, hex forms and MiniMessage, and inserts player-supplied values literally.
- **Bedrock:** `platform/BedrockProbe` detects Floodgate players; the `bedrock` action is a sell shortcut in the same inventory UI. No Bedrock form UI.
- **Commands/permissions:** `/bspawner give|list|doctor|reload|booster|boosteritem|sellwand|modifier|menu|corrupt|repair`, `/bmodify`, `/bhtoggle`. Permission nodes are only `bravenspawner.use`, `.admin`, `.item.extract` and `.restrictions.bypass` (PM-01). `menu`/`corrupt`/`repair` pick an owned spawner rather than the targeted block (PM-03).
- Not present: search/sort/filter, withdraw-amount picker, stack/split views, roles beyond owner/trusted, admin control centre, locale files beyond `messages.yml`.

## 1. Navigation structure

```text
/bspawner  →  Spawner Overview
             ├─ Storage / Vault
             │   ├─ Filter / Sort / Search
             │   ├─ Item detail / Withdraw (1,16,64,custom,all-fit)
             │   └─ Confirm Sell / Pending Operations
             ├─ Stack / Split / Bundle
             ├─ Upgrade Centre
             │   ├─ Speed / Yield / Capacity / Fortune / XP
             │   └─ Specialization / Trait / Respec
             ├─ Automation
             │   ├─ Filters / Rules / Export
             │   └─ Refinement / Fuel / Overdrive
             ├─ Braven Core Network
             │   ├─ Nodes / Vault / Synergy / Members
             │   └─ Network Analytics
             ├─ Research / Blueprints / Mastery / Prestige
             └─ Insights / Stats / Help
```

Commands shown as proposal; do not claim subcommands are present until implemented and registered with `plugin.yml`.

## 2. Example 6-row inventory layout

```text
ROW 1 | Back | Title | Owner | Kind | Tier | Units | State | Help | Close
ROW 2 | Storage | Upgrades | Automation | Network | Research | Genome | Stats | Members | Settings
ROW 3 | Capacity summary | Progress | Current output | Upgrade preview ...
ROW 4 | Search/Filter | Item entries/pages (context-dependent) ...
ROW 5 | Session notices | Last transaction | Pending transfer | Warning ...
ROW 6 | Prev | Actions | Confirm/Cancel (only on confirmation screen) | Next
```

Do not rely on one static 54-slot arrangement for every screen: menu-specific inventory sizes and explicit inventory holder types are preferred. Item amount on icon must never fake a virtual quantity outside Bukkit's native stack limit; represent virtual numbers in custom name/lore and an exact-number detail view.

## 3. UX principles

- **Discoverability:** locked features indicate unlock/disabled reason, dependency and expected cost.
- **Predictability:** show current vs projected upgrade effect, full **group cost**, active modifiers, capacity units and cooldown.
- **Safety:** destructive changes (break with vault, merge, split, fusion, prestige, voiding, unbinding Core) require confirm, show item/currency and irreversible consequences.
- **Recovery:** pending transactions display status/claim inbox rather than silently vanishing.
- **Feedback:** failure text names reason (insufficient funds, permission, stale inventory view, network busy, cap exceeded).
- **Accessibility:** high-contrast default palette, no color-only status meaning, plain-text descriptions, no animation dependency, short lore lines, chat command fallback, screen-reader/text accessibility as permitted by client.
- **Bedrock:** provide clear fallback interactions and test with Geyser/Floodgate if advertised; a regular inventory GUI is not automatically accessible on every client.

## 4. Session and click correctness

- Use typed InventoryHolder with session generation, user UUID, view type and spawner/network ID. Verify the *actual currently open inventory instance*.
- Handle inventory click, drag, shift-click, number keys, double-click, collect-to-cursor, drop, creative handling and forced close according to threat model.
- GUI must not carry mutable server truth in lore; transaction service rechecks revision and permission.
- Pagination or menu transitions must not clear the newly opened session because the old `InventoryCloseEvent` arrives afterward.
- Any withdrawal invokes item-count reservation → player inventory insertion on player entity scheduler → confirm accepted count or reconcile unknown handoff.

## 5. Proposed command suite

| Command | Intended action | Permission |
| --- | --- | --- |
| `/bspawner view` | Open selected owned/targeted spawner overview | `bravenspawner.use` |
| `/bspawner storage` | Open personal permitted vault | `bravenspawner.storage.view` |
| `/bspawner withdraw <material> <amount>` | Explicit bounded withdrawal | `bravenspawner.storage.withdraw` |
| `/bspawner stack <units>` / `split <units>` | Ledger-backed bundle transfer | `bravenspawner.stack` |
| `/bspawner upgrades` | Show upgrade centre, buy via confirm | `bravenspawner.upgrades` |
| `/bspawner core` | Core overview for authorized members | `bravenspawner.core.view` |
| `/bspawner research` | Research view | `bravenspawner.research` |
| `/bspawner insight` | Analytics & advisory suggestions | `bravenspawner.insight` |
| `/bspawner admin inspect` | Read-only diagnostics and audit trail | `bravenspawner.admin.inspect` |

Commands must resolve explicit target IDs for remote access, not silently select the "first" owned spawner. Separate read, withdraw, upgrade, transfer, sell, manage trust, fusion and admin rights.

## 6. Admin Control Center

Includes safe inspect; list filtered by owner/world/chunk/controller; total virtual producer units and stock; transaction/payout reconciliation queue; failed checkpoints; backup/schema health; region scheduling metrics; reindex dry-run; recovery actions with audited approval; feature-flag status. Administrative edits must be rate-limited and leave an immutable log of before/after snapshots and actor.

## 7. Locale and presentation

All human strings in versioned language files with fallback. Prefer Adventure Components and MiniMessage/legacy translation as deliberately configured; escape unsanitized player-supplied text and avoid untrusted MiniMessage parsed as tags. Use accessible English/Indonesian localizations when provided and avoid hard-coded formatting in business logic.

## 8. Acceptance

- Clear navigation on Java inventory and tested supported Bedrock clients.
- Owner/viewer/withdrawer/manager permissions apply consistently across GUI and command.
- Spam navigation, rapid close/open and all inventory manipulation cases neither dupe nor lose data.
- Confirm screen quotes expire and revalidate inventory revisions and prices before purchase.
- GUI redraw does not enumerate millions of virtual units, no giant ItemStack allocation.
