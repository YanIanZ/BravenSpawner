package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.econ.Currency;
import dev.yanianz.bravenspawner.econ.CurrencyAction;
import dev.yanianz.bravenspawner.econ.CurrencyMath;
import dev.yanianz.bravenspawner.econ.CurrencyRegistry;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.boosters.Boosters;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.menu.MenuClick;
import dev.yanianz.bravenspawner.menu.MenuDefinition;
import dev.yanianz.bravenspawner.menu.MenuHolder;
import dev.yanianz.bravenspawner.menu.MenuRenderer;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.menu.OrderedPuzzle;
import dev.yanianz.bravenspawner.menu.PurchaseMath;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.platform.HologramConfig;
import dev.yanianz.bravenspawner.sell.SellService;
import dev.yanianz.bravenspawner.util.Messages;
import dev.yanianz.bravenspawner.util.Text;
import dev.yanianz.bravenspawner.wand.SoundFeedback;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chest GUI: a paged storage view plus a control menu.
 *
 * <p>Displayed storage is read-only; action callbacks withdraw from the model, so the inventory can
 * never be used to dupe or to move items the model still owns.
 *
 * <p>Sessions are identified by the window itself, not by the player: every open
 * inventory is attached to a typed {@link GuiHolder} session with the spawner id and
 * a monotonic generation. YAML inventories carry their own {@link MenuHolder}, and
 * an event is only honoured when its inventory is still the current
 * session. Titles are never parsed, so a rapid close/open cannot let the close of
 * an old window clear the session of the new one.
 */
public final class SpawnerGui implements Listener {

    private enum View { MENU, STORAGE, PUZZLE, LOG }

    /** Window in which a second click confirms a sale or a maximum purchase. */
    static final long CONFIRM_MS = 3_000L;
    /** Items taken by a right-click on a storage cell, per the shipped informative lore. */
    static final int RIGHT_CLICK_AMOUNT = 64;

    private static final class LogQuery {
        int type = -1;
        boolean mine;
        int limit = 64;
        long since;
    }

    private record Pending(GuiAction action, UUID spawner, long until) { }

    /**
     * Identity of one open spawner window.
     *
     * <p>The generation is incremented for every window, so a stale holder left in
     * an event queue is distinguishable from the current one even when both point
     * at the same spawner. {@link #getInventory()} is filled in immediately after
     * the inventory is created; Paper does not call it during creation.
     */
    public static final class GuiHolder implements InventoryHolder {

        private final UUID spawnerId;
        private final long generation;
        private final View view;
        private final int page;
        private Inventory inventory;
        private List<Integer> contentSlots = List.of();
        private List<ItemStack> displayed = List.of();
        private OrderedPuzzle puzzle;
        private MenuDefinition definition;
        private ItemStack correct;
        private ItemStack incorrect;
        private LogQuery query;

        private GuiHolder(UUID spawnerId, long generation, View view, int page) {
            this.spawnerId = spawnerId;
            this.generation = generation;
            this.view = view;
            this.page = page;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        /** The spawner this window was built for. */
        public UUID spawnerId() {
            return spawnerId;
        }

        /** Monotonic window number; the current session always has the highest one. */
        public long generation() {
            return generation;
        }

        private boolean menu() {
            return view == View.MENU;
        }

        private int page() {
            return page;
        }
    }

    private final BravenSpawner plugin;
    private final GuiLock locks;
    private final SellService sellService;
    private final MenuService menus;
    private final AtomicLong generations = new AtomicLong();
    private final Map<UUID, GuiHolder> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> takeModes = new ConcurrentHashMap<>();
    private volatile SoundFeedback sounds;
    private volatile boolean soundsUnavailable;

    public SpawnerGui(BravenSpawner plugin) {
        this.plugin = plugin;
        this.locks = GuiLock.of(plugin);
        this.sellService = SellService.install(plugin);
        this.menus = new MenuService(plugin);
    }

    /** The window the player currently owns, or null when they have no spawner GUI open. */
    public GuiHolder session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public MenuService menus() { return menus; }

    /** Reloads menus, messages and sound cues; called from the plugin reload. */
    public int reloadMenus() {
        messages().reload();
        SoundFeedback current = sounds;
        if (current != null) {
            try { current.reload(); } catch (RuntimeException ignored) { sounds = null; }
        }
        soundsUnavailable = false;
        return menus.reload();
    }

    private Messages messages() {
        return Messages.of(plugin);
    }

    private void play(Player player, SoundFeedback.Cue cue) {
        if (player == null || soundsUnavailable) return;
        SoundFeedback feedback = sounds;
        if (feedback == null) {
            try {
                feedback = new SoundFeedback(plugin);
                sounds = feedback;
            } catch (RuntimeException unavailable) {
                soundsUnavailable = true;
                return;
            }
        }
        try {
            feedback.play(player, cue);
        } catch (RuntimeException ignored) {
            // Feedback must never break the action it accompanies.
        }
    }

    public void openMenu(Player player, Spawner spawner) {
        Settings s = plugin.settings();
        if (!authorized(player, spawner)) return;
        // canOpen sends its own refusal, so no second message here.
        if (!locks.canOpen(player.getUniqueId(), spawner.id())) return;

        if (spawner.corrupted()) { openCorruption(player, spawner); return; }
        if (openConfigured(player, spawner, "spawner", 0, 1)) return;

        GuiHolder holder = new GuiHolder(spawner.id(), generations.incrementAndGet(), View.MENU, 0);
        String name = displayName(spawner);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Text.of("&#F7B733✦ &8" + name + " Spawner"));
        holder.attach(inv);
        fill(inv);
        Map<String, String> values = placeholders(player, spawner, 0, 1);
        inv.setItem(4, headButton(plugin.product(spawner), HologramConfig.productColor(spawner.product()) + "&l" + name + " Spawner",
                List.of("&7Speed: &f" + values.get("speed") + "s",
                        "&7Stack: &f" + values.get("stack") + "&7/&f" + values.get("max_stack"),
                        "&7Tier: &f" + spawner.tier())));
        inv.setItem(10, button(Material.CHEST, "&#7EE787&lStorage",
                List.of("&7Items: &f" + values.get("storage_value"),
                        "&7Slots: &f" + spawner.distinctSlots(),
                        "", "&#F7B733» &7Click: &fopen storage")));
        inv.setItem(11, sellButton(player, spawner));
        inv.setItem(12, button(Material.EXPERIENCE_BOTTLE, "&#FFD93D&lCollect XP",
                List.of("&7Stored: &f" + values.get("xp_value"), "", "&#F7B733» &7Click: &fcollect")));
        inv.setItem(13, button(Material.HOPPER, "&#4D96FF&lTake all",
                List.of("&7Moves everything that fits", "&7into your inventory.", "", "&#F7B733» &7Click: &ftake all")));
        inv.setItem(14, button(Material.ANVIL, "&#FF9F45&lUpgrade",
                List.of("&7Tier: &f" + spawner.tier(), "", "&#F7B733» &7Click: &fupgrade")));
        inv.setItem(15, button(Material.NAME_TAG, "&#C780FA&lTrusted players",
                List.of("&7Look at the spawner and use", "&f/bmodify <player>")));
        inv.setItem(16, button(Material.BARRIER, "&#FF7B72&lClose", List.of()));
        inv.setItem(22, button(Material.WRITABLE_BOOK, "&#C9D1D9&lHistory",
                List.of("&7Recent actions on this spawner.", "", "&#F7B733» &7Click: &fview")));

        if (!show(player, inv)) return;
        sessions.put(player.getUniqueId(), holder);
        locks.markOpened(player.getUniqueId(), spawner.id());
        if (s != null && s.enableLogger) {
            plugin.getLogger().info(player.getName() + " opened menu of " + spawner.id());
        }
    }

    public void openStorage(Player player, Spawner spawner, int page) {
        if (!authorized(player, spawner)) return;
        if (spawner.corrupted()) { openCorruption(player, spawner); return; }
        if (openConfiguredStorage(player, spawner, page)) return;

        int rows = 6;
        int perPage = (rows - 1) * 9;
        List<ItemStack> contents = spawner.contents();
        int pages = Math.max(1, (contents.size() + perPage - 1) / perPage);
        int safePage = Math.max(0, Math.min(page, pages - 1));

        UUID viewer = player.getUniqueId();
        boolean sameSpawner = spawner.id().equals(locks.sessionSpawner(viewer));
        if (!sameSpawner && !locks.canOpen(viewer, spawner.id())) return;

        GuiHolder holder = new GuiHolder(spawner.id(), generations.incrementAndGet(), View.STORAGE, safePage);
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                Text.of("&#F7B733✦ &8" + displayName(spawner) + " &7» &8Storage &7(" + (safePage + 1) + "/" + pages + ")"));
        holder.attach(inv);

        List<Integer> slots = new ArrayList<>(perPage);
        for (int i = 0; i < perPage; i++) slots.add(i);
        holder.contentSlots = List.copyOf(slots);
        holder.displayed = contents;
        List<String> hint = List.of("", "&#F7B733» &7Left-click: &ftake 1",
                "&#F7B733» &7Right-click: &ftake 64", "&#F7B733» &7Shift-click: &ftake a stack");
        int start = safePage * perPage;
        for (int i = 0; i < perPage && start + i < contents.size(); i++) {
            inv.setItem(i, display(contents.get(start + i), hint));
        }

        int navRow = (rows - 1) * 9;
        for (int slot = navRow; slot < navRow + 9; slot++) inv.setItem(slot, filler());
        if (safePage > 0) inv.setItem(navRow, button(Material.ARROW, "&#F7B733&lPrevious page", List.of()));
        if (safePage < pages - 1) inv.setItem(navRow + 8, button(Material.ARROW, "&#F7B733&lNext page", List.of()));
        inv.setItem(navRow + 3, button(Material.SPECTRAL_ARROW, "&#C9D1D9&lBack", List.of()));
        inv.setItem(navRow + 4, button(Material.HOPPER, "&#4D96FF&lTake all",
                List.of("&7Moves everything that fits", "&7into your inventory.")));
        inv.setItem(navRow + 5, sellButton(player, spawner));

        if (!show(player, inv)) return;
        sessions.put(viewer, holder);
        locks.markOpened(viewer, spawner.id());
    }

    /** True when no node gates the action or the player holds it; otherwise tells them. */
    private boolean permitted(Player player, ActionPermissions permission) {
        if (permission == null || permission.allows(player)) return true;
        fail(player, "gui.action-denied");
        return false;
    }

    private boolean authorized(Player player, Spawner spawner) {
        if (player == null || spawner == null) return false;
        if (plugin.restrictions() != null && !plugin.restrictions().canOpen(player, spawner)) return false;
        if (plugin.protection() != null && !plugin.protection().canUse(player, spawner.blockLocation())) return false;
        if (plugin.mayUse(player, spawner)) return true;
        messages().send(player, "gui.not-yours");
        return false;
    }

    private String displayName(Spawner spawner) {
        var product = plugin.product(spawner);
        String name = product == null ? null : product.displayName();
        if (name == null || name.isBlank()) name = spawner.product() == null ? "Spawner" : spawner.product();
        return name;
    }

    private Map<String, String> placeholders(Player player, Spawner spawner, int page, int pages) {
        var product = plugin.product(spawner);
        Settings settings = plugin.settings();
        int stack = Math.max(1, spawner.stack());
        long minTicks;
        long maxTicks;
        if (product != null && spawner.tier() == 0) {
            minTicks = product.intervalTicks();
            maxTicks = product.maxIntervalTicks();
        } else if (settings != null && settings.tiers != null && !settings.tiers.isEmpty()) {
            Settings.Tier tier = settings.tiers.get(Math.min(Math.max(0, spawner.tier()), settings.tiers.size() - 1));
            minTicks = maxTicks = Math.max(20L, tier.intervalTicks);
        } else {
            minTicks = maxTicks = product == null ? 0 : product.intervalTicks();
        }
        double speed = plugin.boosters() == null ? 1.0
                : plugin.boosters().multiplier(Boosters.Type.SPEED, spawner.owner(), spawner.id());
        if (Double.isFinite(speed) && speed > 0.0 && speed != 1.0) {
            minTicks = Math.max(20L, Math.round(minTicks / speed));
            maxTicks = Math.max(20L, Math.round(maxTicks / speed));
        }
        int dropMin = 0;
        int dropMax = 0;
        if (product != null) {
            for (var drop : product.drops()) {
                if (drop.chance() >= 1.0) dropMin += drop.minAmount();
                dropMax += drop.maxAmount();
            }
        }
        double price = product == null ? 0.0 : product.spawnerPrice();
        int limit = plugin.stackLimit(spawner);
        int room = spawner.roomFor(limit);
        int batch = room <= 0 ? 64 : Math.min(64, room);
        Currency buyCurrency = CurrencyRegistry.select(plugin, CurrencyAction.BUY_STACK);
        MenuPlaceholders.Snapshot snapshot = new MenuPlaceholders.Snapshot(
                spawner.product(), displayName(spawner), HologramConfig.productColor(spawner.product()),
                spawner.tier(), spawner.stack(), limit,
                spawner.storedCount(), plugin.maximumItems(spawner), spawner.storedXp(), plugin.maximumXp(spawner),
                sellService.format(sellService.value(player, spawner)),
                buyCurrency.format(price), buyCurrency.format(price * batch),
                minTicks, maxTicks,
                (int) Math.min(Integer.MAX_VALUE, (long) dropMin * stack),
                (int) Math.min(Integer.MAX_VALUE, (long) dropMax * stack),
                (int) Math.min(Integer.MAX_VALUE, (long) (product == null ? 0 : product.xp()) * stack),
                (int) Math.min(Integer.MAX_VALUE, (long) (product == null ? 0 : product.maxXpPerCycle()) * stack),
                page, pages,
                product == null ? "" : product.headTexture(),
                headIcon(product).name());
        Map<String, String> values = MenuPlaceholders.build(snapshot);
        Messages messages = messages();
        values.put("player", messages.format("log.all"));
        values.put("logtype", messages.format("log.all"));
        values.put("time", messages.format("log.all-time"));
        return values;
    }

    private String layoutFor(Player player, MenuDefinition definition) {
        boolean bedrock = plugin.platform() != null && plugin.platform().configured() && plugin.platform().isBedrock(player);
        return bedrock && definition.hasLayout("bedrock_mode") ? "bedrock_mode" : definition.defaultLayout();
    }

    private boolean openConfigured(Player player, Spawner spawner, String name, int page, int pages) {
        MenuDefinition definition = menus.definition(name);
        if (definition == null) return false;
        UUID viewer = player.getUniqueId();
        if (!authorized(player, spawner)) return true;
        if (!spawner.id().equals(locks.sessionSpawner(viewer)) && !locks.canOpen(viewer, spawner.id())) return true;
        Map<Character, MenuClick> actions = new LinkedHashMap<>();
        for (var entry : definition.items().entrySet()) {
            String action = entry.getValue().action();
            actions.put(entry.getKey(), (clicker, click) -> {
                GuiHolder current = session(clicker);
                MenuHolder window = menus.session(clicker);
                if (current == null || window == null || current.getInventory() != window.getInventory()) return;
                Spawner live = plugin.manager() == null ? null : plugin.manager().byId(current.spawnerId());
                if (live == null || !authorized(clicker, live)
                        || !live.id().equals(locks.sessionSpawner(clicker.getUniqueId()))) {
                    clicker.closeInventory();
                    return;
                }
                if (live.corrupted() && !name.equals("corruption")) {
                    openCorruption(clicker, live);
                    return;
                }
                configuredAction(clicker, live, name, action, page, click);
            });
        }
        String layout = name.equals("spawner") || name.equals("storage") ? layoutFor(player, definition)
                : definition.defaultLayout();
        Map<String, String> values = placeholders(player, spawner, page, pages);
        MenuHolder opened = menus.open(player, name, layout, values, actions);
        if (opened == null) return true;
        GuiHolder session = new GuiHolder(spawner.id(), generations.incrementAndGet(),
                name.equals("storage") ? View.STORAGE : name.equals("corruption") ? View.PUZZLE
                        : name.equals("log") ? View.LOG : View.MENU, page);
        session.attach(opened.getInventory());
        sessions.put(viewer, session);
        locks.markOpened(viewer, spawner.id());
        decorate(player, spawner, definition, opened);
        return true;
    }

    /** Dynamic lore the static template cannot express: active boosters and the selected take mode. */
    private void decorate(Player player, Spawner spawner, MenuDefinition definition, MenuHolder opened) {
        Inventory inventory = opened.getInventory();
        if (inventory == null) return;
        String layout = opened.layout();
        String boosterFormat = definition.option("special.booster_display", "");
        List<String> boosterLines = boosterFormat.isBlank() ? List.of() : boosterLines(spawner, boosterFormat);
        int mode = takeModes.getOrDefault(player.getUniqueId(), 1);
        String selected = definition.option("selected_text", "&7 (&aSELECTED&7)");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            MenuDefinition.ItemDefinition item = definition.item(definition.symbol(layout, slot));
            if (item == null) continue;
            GuiAction action = GuiAction.parse(item.action());
            if (action == null) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) continue;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) continue;
            boolean changed = false;
            if (action == GuiAction.BOOSTERS && !boosterLines.isEmpty()) {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.addAll(Text.items(boosterLines));
                meta.lore(lore);
                changed = true;
            }
            if ((action == GuiAction.TAKE_ONE && mode == 1)
                    || (action == GuiAction.TAKE_STACK && mode == RIGHT_CLICK_AMOUNT)) {
                Component name = meta.displayName() == null ? Component.empty() : meta.displayName();
                meta.displayName(name.append(Text.item(selected)));
                changed = true;
            }
            if (changed) stack.setItemMeta(meta);
        }
    }

    private List<String> boosterLines(Spawner spawner, String format) {
        Boosters boosters = plugin.boosters();
        if (boosters == null) return List.of();
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            for (var effect : boosters.activeFor(spawner.owner(), spawner.id())) {
                String remaining = effect.expiresAt() == Long.MAX_VALUE ? "∞"
                        : duration(Math.max(0L, (effect.expiresAt() - now) / 1000L));
                String name = effect.type().name().charAt(0)
                        + effect.type().name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
                lines.add(format.replace("%booster_name%", name)
                        .replace("%booster_multiplier%", "x" + trim(effect.multiplier()))
                        .replace("%remaining_time%", remaining));
            }
        } catch (RuntimeException unavailable) {
            return List.of();
        }
        return lines;
    }

    static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + rest + "s";
        return rest + "s";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** Content cells are filler cells above the last control row. Controls may be moved freely. */
    private List<Integer> storageSlots(MenuDefinition definition, String layout) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < (definition.rows() - 1) * 9; slot++) {
            char symbol = definition.symbol(layout, slot);
            if (symbol == 0 || symbol == definition.filler()) slots.add(slot);
        }
        return slots;
    }

    private boolean openConfiguredStorage(Player player, Spawner spawner, int requested) {
        MenuDefinition definition = menus.definition("storage");
        if (definition == null) return false;
        List<Integer> slots = storageSlots(definition, layoutFor(player, definition));
        if (slots.isEmpty()) return false;
        List<ItemStack> contents = spawner.contents();
        int pages = Math.max(1, (contents.size() + slots.size() - 1) / slots.size());
        int maxPages = definition.number("max_page_amount", 0);
        if (maxPages > 0) pages = Math.min(pages, maxPages);
        int page = Math.max(0, Math.min(requested, pages - 1));
        MenuHolder previous = menus.session(player);
        if (!openConfigured(player, spawner, "storage", page, pages)) return false;
        if (menus.session(player) == previous) return true;
        GuiHolder current = session(player);
        if (current == null || !current.spawnerId().equals(spawner.id()) || current.page() != page) return true;
        current.contentSlots = List.copyOf(slots);
        current.displayed = contents.stream().map(ItemStack::clone).toList();
        List<String> hint = definition.flag("enable_informative_lore", false)
                ? definition.lines("informative_lore") : List.of();
        for (int i = 0; i < slots.size(); i++) {
            int index = page * slots.size() + i;
            ItemStack display = index < contents.size() ? display(contents.get(index), hint) : null;
            current.getInventory().setItem(slots.get(i), display);
        }
        return true;
    }

    /**
     * A view-only copy (never the stored instance) capped at one vanilla stack, with the
     * true stored amount and the hint lines appended to its lore.
     */
    private ItemStack display(ItemStack stored, List<String> hint) {
        if (stored == null) return null;
        int amount = stored.getAmount();
        ItemStack display = stored.clone();
        if (display == null) return null;
        display.setAmount(StorageDisplay.visibleAmount(amount, display.getMaxStackSize()));
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.item(StorageDisplay.amountLore(messages(), amount)));
            lore.addAll(Text.items(hint));
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private boolean canViewLog(Player player, Spawner spawner) {
        return player.hasPermission("bravenspawner.admin") || spawner.isOwner(player.getUniqueId());
    }

    public void openLog(Player player, Spawner spawner, int requestedPage) {
        if (!canViewLog(player, spawner)) {
            messages().send(player, "gui.log-denied");
            return;
        }
        MenuDefinition definition = menus.definition("log");
        if (definition == null || plugin.audit() == null) return;
        List<Integer> slots = storageSlots(definition, definition.defaultLayout());
        if (slots.isEmpty()) return;
        GuiHolder before = session(player);
        LogQuery query = before != null && before.query != null ? before.query : new LogQuery();
        List<AuditEvent> events = plugin.audit().recent(spawner.id(), 64).stream()
                .filter(event -> !query.mine || event.actor().equals(player.getUniqueId()))
                .filter(event -> query.type < 0 || event.action() == AuditEvent.Action.values()[query.type])
                .filter(event -> query.since == 0 || event.timestamp() >= System.currentTimeMillis() - query.since)
                .limit(query.limit).toList();
        int pages = Math.max(1, (events.size() + slots.size() - 1) / slots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder previous = menus.session(player);
        openConfigured(player, spawner, "log", page, pages);
        if (menus.session(player) == previous) return;
        GuiHolder current = session(player);
        if (current == null || current.view != View.LOG) return;
        current.query = query;
        Messages messages = messages();
        DateTimeFormatter formatter = timeFormatter(messages);
        for (int cell = 0; cell < slots.size(); cell++) {
            int index = page * slots.size() + cell;
            if (index >= events.size()) { current.getInventory().setItem(slots.get(cell), null); continue; }
            AuditEvent event = events.get(index);
            Object[] values = {
                    "action", pretty(event.action().name()),
                    "time", formatter.format(Instant.ofEpochMilli(event.timestamp()).atZone(ZoneId.systemDefault())),
                    "player", event.actorName(),
                    "amount", MenuPlaceholders.number(event.amount()),
                    "world", event.world().isEmpty() ? "-" : event.world(),
                    "x", event.x(), "y", event.y(), "z", event.z(),
                    "detail", event.detail()};
            List<String> lore = new ArrayList<>(messages.lines("log.entry-lore", values));
            lore.removeIf(line -> Text.strip(line).isBlank());
            current.getInventory().setItem(slots.get(cell),
                    button(Material.PAPER, messages.format("log.entry-name", values), lore));
        }
        Map<String, String> values = new LinkedHashMap<>(placeholders(player, spawner, page, pages));
        values.put("player", query.mine ? Text.escape(player.getName()) : messages.format("log.all"));
        values.put("logtype", query.type < 0 ? messages.format("log.all")
                : pretty(AuditEvent.Action.values()[query.type].name()));
        values.put("limit", String.valueOf(query.limit));
        values.put("time", messages.format(query.since == 0 ? "log.all-time"
                : query.since == 3_600_000 ? "log.last-hour" : "log.last-day"));
        for (int slot = 0; slot < definition.rows() * 9; slot++) {
            char symbol = definition.symbol(definition.defaultLayout(), slot);
            if (symbol != definition.filler() && symbol != 0) {
                current.getInventory().setItem(slot, menuItem(player, definition.item(symbol), values));
            }
        }
    }

    static DateTimeFormatter timeFormatter(Messages messages) {
        try {
            return DateTimeFormatter.ofPattern(messages.template("log.time-format"), Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
        }
    }

    private static String pretty(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void updateLogQuery(Player player, Spawner spawner, GuiAction action) {
        if (!canViewLog(player, spawner)) return;
        GuiHolder current = session(player);
        if (current == null || current.query == null) return;
        LogQuery query = current.query;
        switch (action) {
            case LOG_TARGET -> query.mine = !query.mine;
            case LOG_TYPE -> query.type = query.type + 1 >= AuditEvent.Action.values().length ? -1 : query.type + 1;
            case LOG_LIMIT -> query.limit = query.limit == 64 ? 16 : query.limit == 16 ? 32 : 64;
            case LOG_TIME -> query.since = query.since == 0 ? 3_600_000 : query.since == 3_600_000 ? 86_400_000 : 0;
            default -> { }
        }
        openLog(player, spawner, 0);
    }

    private void openCorruption(Player player, Spawner spawner) {
        MenuDefinition definition = menus.definition("corruption");
        if (definition == null || definition.puzzleSlots().isEmpty()) {
            messages().send(player, "gui.corruption-unavailable");
            return;
        }
        MenuHolder previous = menus.session(player);
        openConfigured(player, spawner, "corruption", 0, 1);
        if (menus.session(player) == previous) return;
        GuiHolder current = session(player);
        if (current == null || current.view != View.PUZZLE) return;
        current.definition = definition;
        current.puzzle = new OrderedPuzzle(definition.puzzleSlots());
        current.correct = menuItem(player, definition.item("correct"), placeholders(player, spawner, 0, 1));
        current.incorrect = menuItem(player, definition.item("incorrect"), placeholders(player, spawner, 0, 1));
        resetPuzzle(current);
    }

    private ItemStack menuItem(Player player, MenuDefinition.ItemDefinition item, Map<String, String> values) {
        return MenuRenderer.buildItem(item, values, text -> plugin.settings() != null
                && plugin.settings().placeholdersInGui && plugin.placeholders() != null
                ? plugin.placeholders().apply(player, text) : text);
    }

    private void resetPuzzle(GuiHolder current) {
        for (int slot : current.definition.puzzleSlots()) {
            current.getInventory().setItem(slot, current.incorrect == null ? null : current.incorrect.clone());
        }
    }

    private void puzzleClick(Player player, Spawner spawner, GuiHolder current, int slot) {
        if (current.puzzle == null) return;
        OrderedPuzzle.Result result = current.puzzle.click(slot);
        switch (result) {
            case IGNORED -> { }
            case RESET -> {
                resetPuzzle(current);
                puzzleSound(player, current.definition, "incorrect");
                String message = current.definition.message("reset");
                if (!message.isBlank()) player.sendMessage(Text.of(messages().prefix() + message));
            }
            case CORRECT -> {
                current.getInventory().setItem(slot, current.correct == null ? null : current.correct.clone());
                puzzleSound(player, current.definition, "correct");
            }
            case COMPLETE -> {
                player.closeInventory();
                plugin.corruption().repairPuzzle(player, spawner);
                plugin.markDirty(spawner);
                puzzleSound(player, current.definition, "completion");
                String message = current.definition.message("completion");
                if (!message.isBlank()) player.sendMessage(Text.of(messages().prefix() + message));
            }
        }
    }

    private void puzzleSound(Player player, MenuDefinition definition, String event) {
        String sound = definition.sound(event);
        if (sound.isBlank()) return;
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(sound), 1F, 1F);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Invalid corruption sound: " + sound);
        }
    }

    public void openBuyStack(Player player, Spawner spawner) {
        openConfigured(player, spawner, "buy_stack", 0, 1);
    }

    private void buyStack(Player player, Spawner spawner, int requested) {
        if (!permitted(player, ActionPermissions.BUY_STACK)) return;
        if (!authorized(player, spawner)) return;
        var product = plugin.product(spawner);
        double price = product == null ? 0.0 : product.spawnerPrice();
        if (!Double.isFinite(price) || price <= 0) {
            fail(player, "gui.buy.unavailable");
            return;
        }
        Currency currency = CurrencyRegistry.select(plugin, CurrencyAction.BUY_STACK);
        if (!currency.available()) {
            fail(player, "gui.buy.currency-unavailable", currencyValues(currency));
            return;
        }
        double unit = CurrencyMath.charge(price, currency.wholeUnits());
        var world = Bukkit.getWorld(spawner.world());
        if (world == null) return;
        int chunkRoom = plugin.manager().chunkUnitRoom(world, spawner.x() >> 4, spawner.z() >> 4);
        int limit = plugin.stackLimit(spawner);
        int count = PurchaseMath.quantity(requested, spawner.roomFor(limit), chunkRoom,
                currency.balance(player), unit);
        if (count <= 0) {
            fail(player, "gui.buy.no-room");
            return;
        }
        double total = count * unit;
        if (!Double.isFinite(total) || !currency.withdraw(player, total).success()) {
            fail(player, "gui.buy.payment-failed", currencyValues(currency));
            return;
        }
        if (!spawner.tryAddStack(count, limit)) {
            if (!currency.deposit(player, total).success()) {
                plugin.getLogger().severe("Failed to refund spawner purchase for " + player.getUniqueId() + ": " + total);
            }
            fail(player, "gui.buy.refunded");
            return;
        }
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.STACK, player, count, "buy_stack: " + total);
        plugin.manager().reschedule(spawner);
        messages().send(player, "gui.buy.success", "amount", count,
                "price", Messages.raw(Text.escape(currency.format(total))),
                "currency", currency.displayName(), "currency_symbol", currency.symbol());
        play(player, SoundFeedback.Cue.STACKING);
        openBuyStack(player, spawner);
    }

    /** {@code %currency%} and {@code %currency_symbol%} appended to the given placeholder pairs. */
    private static Object[] currencyValues(Currency currency, Object... values) {
        Object[] out = java.util.Arrays.copyOf(values, values.length + 4);
        out[values.length] = "currency";
        out[values.length + 1] = currency.displayName();
        out[values.length + 2] = "currency_symbol";
        out[values.length + 3] = currency.symbol();
        return out;
    }

    private void fail(Player player, String key, Object... values) {
        messages().send(player, key, values);
        play(player, SoundFeedback.Cue.ERROR);
    }

    /**
     * Requires a second click on the same action and spawner within {@link #CONFIRM_MS}.
     *
     * @return true when this click confirms a pending request
     */
    boolean confirmed(Player player, Spawner spawner, GuiAction action, String description) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Pending pending = confirmations.get(id);
        if (pending != null && pending.action() == action && pending.spawner().equals(spawner.id())
                && pending.until() >= now) {
            confirmations.remove(id, pending);
            return true;
        }
        confirmations.put(id, new Pending(action, spawner.id(), now + CONFIRM_MS));
        messages().send(player, "gui.confirm", "seconds", CONFIRM_MS / 1000, "action", Messages.raw(description));
        return false;
    }

    private String sellDescription(Player player, Spawner spawner, String key) {
        return messages().format(key,
                "items", MenuPlaceholders.number(sellService.sellableItems(player, spawner)),
                "value", sellService.format(sellService.value(player, spawner)),
                "xp", MenuPlaceholders.number(spawner.storedXp()));
    }

    /** Reopens whichever view the action came from, so the player stays where they were. */
    private void reopen(Player player, Spawner spawner, String menu, int page) {
        switch (menu) {
            case "storage" -> openStorage(player, spawner, page);
            case "buy_stack" -> openBuyStack(player, spawner);
            case "log" -> openLog(player, spawner, page);
            default -> openMenu(player, spawner);
        }
    }

    private void configuredAction(Player player, Spawner spawner, String menu, String raw, int page, ClickType click) {
        GuiAction action = GuiAction.parse(raw);
        if (action == null) return;
        perform(player, spawner, menu, action, page, click == null ? ClickType.LEFT : click);
    }

    private void perform(Player player, Spawner spawner, String menu, GuiAction action, int page, ClickType click) {
        play(player, SoundFeedback.Cue.UI_CLICK);
        if (!permitted(player, ActionPermissions.of(action))) return;
        switch (action) {
            case STORAGE -> {
                MenuDefinition definition = menus.definition("spawner");
                boolean sellOnRight = definition == null || definition.flag("special.allow_sell_with_storage", true);
                if (click.isRightClick() && sellOnRight) {
                    if (!permitted(player, ActionPermissions.SELL)) return;
                    sellWithConfirmation(player, spawner, menu, page, GuiAction.STORAGE);
                } else {
                    openStorage(player, spawner, 0);
                }
            }
            case COLLECT_XP -> {
                collectXp(player, spawner);
                reopen(player, spawner, menu, page);
            }
            case TAKE_ALL -> {
                collectAll(player, spawner);
                reopen(player, spawner, menu, page);
            }
            case SELL_ALL -> sellWithConfirmation(player, spawner, menu, page, GuiAction.SELL_ALL);
            case COLLECT_ALL -> {
                // Each half needs its own node; a player holding neither is told so
                // instead of being asked to confirm a collection that does nothing.
                boolean maySell = ActionPermissions.SELL.allows(player);
                boolean mayXp = ActionPermissions.COLLECT_XP.allows(player);
                if (!maySell && !mayXp) {
                    fail(player, "gui.action-denied");
                    return;
                }
                boolean anything = (maySell && sellService.sellableItems(player, spawner) > 0)
                        || (mayXp && spawner.storedXp() > 0);
                if (anything && !confirmed(player, spawner, GuiAction.COLLECT_ALL,
                        sellDescription(player, spawner, "gui.confirm-collect"))) return;
                if (maySell && sellService.sellableItems(player, spawner) > 0) sellAll(player, spawner);
                if (mayXp && spawner.storedXp() > 0) collectXp(player, spawner);
                if (!anything) messages().send(player, "gui.take.nothing");
                reopen(player, spawner, menu, page);
            }
            case TAKE_ONE, TAKE_STACK -> {
                boolean one = action == GuiAction.TAKE_ONE;
                takeModes.put(player.getUniqueId(), one ? 1 : RIGHT_CLICK_AMOUNT);
                messages().send(player, one ? "gui.take-mode-one" : "gui.take-mode-stack");
                reopen(player, spawner, menu, page);
            }
            case DROP_ALL -> {
                dropPage(player, spawner);
                reopen(player, spawner, menu, page);
            }
            case PREVIOUS -> {
                if (menu.equals("log")) openLog(player, spawner, page - 1);
                else openStorage(player, spawner, page - 1);
            }
            case NEXT -> {
                if (menu.equals("log")) openLog(player, spawner, page + 1);
                else openStorage(player, spawner, page + 1);
            }
            case BACK -> openMenu(player, spawner);
            case CLOSE -> player.closeInventory();
            case UPGRADE -> upgrade(player, spawner);
            case TRUSTED -> messages().send(player, "gui.trusted-hint");
            case BUY_STACK_MENU -> openBuyStack(player, spawner);
            case LOG -> openLog(player, spawner, 0);
            case LOG_TARGET, LOG_TYPE, LOG_LIMIT, LOG_TIME -> {
                if (menu.equals("log")) updateLogQuery(player, spawner, action);
            }
            case BUY_ONE -> buyStack(player, spawner, 1);
            case BUY_64 -> buyStack(player, spawner, 64);
            case BUY_MAX -> {
                var product = plugin.product(spawner);
                String price = CurrencyRegistry.select(plugin, CurrencyAction.BUY_STACK)
                        .format(product == null ? 0.0 : product.spawnerPrice());
                if (confirmed(player, spawner, GuiAction.BUY_MAX,
                        messages().format("gui.confirm-buy-max", "price", price))) {
                    buyStack(player, spawner, Integer.MAX_VALUE);
                }
            }
            case BOOSTERS -> messages().send(player, "gui.boosters-hint");
        }
    }

    private void sellWithConfirmation(Player player, Spawner spawner, String menu, int page, GuiAction key) {
        if (sellService.enabled() && sellService.sellableItems(player, spawner) > 0
                && !confirmed(player, spawner, key, sellDescription(player, spawner, "gui.confirm-sell"))) {
            return;
        }
        sellAll(player, spawner);
        reopen(player, spawner, menu, page);
    }

    /**
     * Opens the window and records the session only when the server accepted it.
     *
     * <p>Recording after {@link Player#openInventory(Inventory)} matters: that call
     * fires the close event of the previous window first, and the close handler
     * must still see the old holder as current — otherwise a page change would
     * clear the brand-new session it is replacing.
     */
    private boolean show(Player player, Inventory inventory) {
        player.openInventory(inventory);
        return player.getOpenInventory().getTopInventory() == inventory;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            onConfiguredDataClick(event);
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) return;

        // Cancel before resolving anything: this covers shift-click, double-click,
        // hotbar swaps and clicks in the player's own rows, so no item can move
        // through a spawner window even from a stale one.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        GuiHolder current = sessions.get(player.getUniqueId());
        if (current == null || current.generation() != holder.generation()) return;

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked != event.getView().getTopInventory()) return;

        int slot = event.getSlot();
        ClickType click = event.getClick() == null ? ClickType.LEFT : event.getClick();
        dispatch(player, current, () -> {
            Spawner spawner = liveSession(player, current);
            if (spawner == null) return;
            if (current.menu()) handleMenu(player, spawner, slot, click);
            else if (current.contentSlots.contains(slot)) cellClick(player, spawner, current, slot, click);
            else handleStorage(player, spawner, current.page(), slot, click);
        });
    }

    private void onConfiguredDataClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        GuiHolder current = session(player);
        Inventory top = event.getView().getTopInventory();
        if (current == null || current.getInventory() != top
                || event.getClickedInventory() != top) return;
        int slot = event.getSlot();
        if (current.view != View.PUZZLE
                && (current.view != View.STORAGE || !current.contentSlots.contains(slot))) return;
        ClickType click = event.getClick() == null ? ClickType.LEFT : event.getClick();
        dispatch(player, current, () -> {
            Spawner spawner = liveSession(player, current);
            if (spawner == null) return;
            if (current.view == View.PUZZLE) puzzleClick(player, spawner, current, slot);
            else cellClick(player, spawner, current, slot, click);
        });
    }

    /** A queued click cannot reopen a closed window or mutate a replacement session. */
    private void dispatch(Player player, GuiHolder holder, Runnable action) {
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, player, () -> {
            if (player.isOnline() && session(player) == holder
                    && player.getOpenInventory().getTopInventory() == holder.getInventory()) {
                action.run();
            }
        }, null);
    }

    /** Resolve authorization and the live model after the scheduler boundary. */
    private Spawner liveSession(Player player, GuiHolder holder) {
        Spawner spawner = plugin.manager() == null ? null : plugin.manager().byId(holder.spawnerId());
        if (spawner == null || !authorized(player, spawner)
                || !spawner.id().equals(locks.sessionSpawner(player.getUniqueId()))) {
            player.closeInventory();
            return null;
        }
        return spawner;
    }

    /** Amount a click on a storage cell takes: left = take mode (1 by default), right = 64, shift = one stack. */
    int cellAmount(Player player, ClickType click, ItemStack expected) {
        int max = Math.max(1, expected.getMaxStackSize());
        return switch (click) {
            case LEFT -> Math.min(expected.getAmount(), takeModes.getOrDefault(player.getUniqueId(), 1));
            case RIGHT -> Math.min(expected.getAmount(), RIGHT_CLICK_AMOUNT);
            case SHIFT_LEFT, SHIFT_RIGHT -> Math.min(expected.getAmount(), max);
            default -> 0;
        };
    }

    private void cellClick(Player player, Spawner spawner, GuiHolder current, int slot, ClickType click) {
        int cell = current.contentSlots.indexOf(slot);
        if (cell < 0) return;
        if (spawner.corrupted()) { openCorruption(player, spawner); return; }
        int index = current.page() * current.contentSlots.size() + cell;
        if (index >= current.displayed.size()) return;
        ItemStack expected = current.displayed.get(index);
        int amount = cellAmount(player, click, expected);
        if (amount <= 0) return;
        if (!player.hasPermission("bravenspawner.item.extract")) {
            fail(player, "gui.extract-denied");
            return;
        }
        if (plugin.restrictions() != null && !plugin.restrictions().canTakeItems(player, spawner)) return;
        ItemStack withdrawn = spawner.drainSlotIfSimilar(index, expected, amount);
        if (withdrawn != null) {
            Withdrawals.Result result = Withdrawals.deliver(player, spawner, List.of(withdrawn));
            plugin.markDirty(spawner);
            if (result.moved() > 0) {
                plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, result.moved(), "storage cell");
                play(player, SoundFeedback.Cue.WITHDRAW);
            } else {
                fail(player, "gui.take.full");
            }
        }
        openStorage(player, spawner, current.page());
    }

    /**
     * Cancels drags that involve a spawner window.
     *
     * <p>Shift-drag can move items from the player inventory into the GUI in one
     * event that never produces a plain click, so the click guard alone would let
     * a player insert an item into a read-only view.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder)) return;
        event.setCancelled(true);
    }

    private void handleMenu(Player player, Spawner spawner, int slot, ClickType click) {
        GuiAction action = switch (slot) {
            case 10 -> GuiAction.STORAGE;
            case 11 -> GuiAction.SELL_ALL;
            case 12 -> GuiAction.COLLECT_XP;
            case 13 -> GuiAction.TAKE_ALL;
            case 14 -> GuiAction.UPGRADE;
            case 15 -> GuiAction.TRUSTED;
            case 16 -> GuiAction.CLOSE;
            case 22 -> GuiAction.LOG;
            default -> null;
        };
        if (action == GuiAction.STORAGE) click = ClickType.LEFT;
        if (action != null) perform(player, spawner, "spawner", action, 0, click);
    }

    private void handleStorage(Player player, Spawner spawner, int page, int slot, ClickType click) {
        int navRow = 45;
        GuiAction action;
        if (slot == navRow) action = GuiAction.PREVIOUS;
        else if (slot == navRow + 8) action = GuiAction.NEXT;
        else if (slot == navRow + 3) action = GuiAction.BACK;
        else if (slot == navRow + 4) action = GuiAction.TAKE_ALL;
        else if (slot == navRow + 5) action = GuiAction.SELL_ALL;
        else return;
        perform(player, spawner, "storage", action, page, click);
    }

    private void collectXp(Player player, Spawner spawner) {
        if (!permitted(player, ActionPermissions.COLLECT_XP)) return;
        synchronized (spawner) {
        var product = plugin.product(spawner);
        double eventMultiplier = plugin.events() == null ? 1.0 : plugin.events().xpMultiplier();
        double personalMultiplier = plugin.boosters() == null ? 1.0
                : plugin.boosters().personalMultiplier(Boosters.Type.XP, player.getUniqueId());
        long payout = Math.min(Integer.MAX_VALUE, Math.round(Math.round(spawner.storedXp() * eventMultiplier) * personalMultiplier));
        long full = Math.min(Integer.MAX_VALUE, Math.round(plugin.maximumXp(spawner) * eventMultiplier));
        if (product != null && product.mustBeFullToCollect() && payout < full) {
            fail(player, "gui.xp.must-be-full");
            return;
        }
        if (payout <= 0) {
            messages().send(player, "gui.xp.none");
            return;
        }
        int banked = spawner.takeXp();
        int xp = (int) Math.min(Integer.MAX_VALUE,
                Math.round(Math.round(banked * eventMultiplier) * personalMultiplier));
        if (plugin.settings().dropXpAsOrbs) {
            var loc = spawner.location();
            if (loc != null) {
                dev.yanianz.bravenspawner.util.Schedulers.at(plugin, loc, () ->
                        loc.getWorld().spawn(loc, org.bukkit.entity.ExperienceOrb.class, orb -> orb.setExperience(xp)));
            } else player.giveExp(xp);
        } else {
            player.giveExp(xp);
        }
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, xp, "XP");
        messages().send(player, "gui.xp.collected", "xp", MenuPlaceholders.number(xp));
        play(player, SoundFeedback.Cue.SELL_XP);
        }
    }

    private void collectAll(Player player, Spawner spawner) {
        if (plugin.restrictions() != null && !plugin.restrictions().canTakeItems(player, spawner)) return;
        if (!player.hasPermission("bravenspawner.item.extract")) {
            fail(player, "gui.extract-denied");
            return;
        }
        List<ItemStack> items = spawner.drainAll();
        if (items.isEmpty()) {
            messages().send(player, "gui.take.nothing");
            return;
        }
        Withdrawals.Result result = Withdrawals.deliver(player, spawner, items);
        plugin.markDirty(spawner);
        if (result.moved() > 0) {
            plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, result.moved(), "take all");
            play(player, SoundFeedback.Cue.WITHDRAW);
        }
        if (result.moved() <= 0) fail(player, "gui.take.full");
        else if (result.returned() > 0) messages().send(player, "gui.take.partial",
                "amount", MenuPlaceholders.number(result.moved()), "left", MenuPlaceholders.number(result.returned()));
        else messages().send(player, "gui.take.done", "amount", MenuPlaceholders.number(result.moved()));
    }

    /** Drops one vanilla stack of every item shown on the player's current storage page. */
    private void dropPage(Player player, Spawner spawner) {
        GuiHolder current = session(player);
        if (current == null || current.view != View.STORAGE || current.contentSlots.isEmpty()) return;
        if (!player.hasPermission("bravenspawner.item.extract")) {
            fail(player, "gui.extract-denied");
            return;
        }
        if (plugin.restrictions() != null && !plugin.restrictions().canTakeItems(player, spawner)) return;
        int first = current.page() * current.contentSlots.size();
        int last = Math.min(current.displayed.size(), first + current.contentSlots.size()) - 1;
        long dropped = 0;
        // Descending, so a fully drained cell cannot shift the indices still to visit.
        for (int index = last; index >= first; index--) {
            ItemStack expected = current.displayed.get(index);
            int amount = Math.min(expected.getAmount(), Math.max(1, expected.getMaxStackSize()));
            ItemStack withdrawn = spawner.drainSlotIfSimilar(index, expected, amount);
            if (withdrawn == null || withdrawn.getAmount() <= 0) continue;
            player.getWorld().dropItemNaturally(player.getLocation(), withdrawn);
            dropped += withdrawn.getAmount();
        }
        if (dropped <= 0) {
            messages().send(player, "gui.take.nothing");
            return;
        }
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, dropped, "drop page");
        messages().send(player, "gui.drop.done", "amount", MenuPlaceholders.number(dropped));
        play(player, SoundFeedback.Cue.WITHDRAW);
    }

    private void sellAll(Player player, Spawner spawner) {
        if (!permitted(player, ActionPermissions.SELL)) return;
        var product = plugin.product(spawner);
        if (product != null && product.mustBeFullToCollect()
                && spawner.storedCount() < plugin.maximumItems(spawner)) {
            fail(player, "gui.sell.must-be-full");
            return;
        }
        SellService.Sale sale = sellService.sell(player, spawner);
        switch (sale.status()) {
            case SOLD -> {
                messages().send(player, "gui.sell.sold", currencyValues(sellService.currency(),
                        "items", MenuPlaceholders.number(sale.items()), "value", sellService.format(sale.value())));
                play(player, SoundFeedback.Cue.SELL_STOCK);
            }
            case NOTHING_TO_SELL -> messages().send(player, "gui.sell.nothing");
            case DISABLED -> fail(player, "gui.sell.disabled");
            case NO_ECONOMY -> fail(player, "gui.sell.no-economy", currencyValues(sellService.currency()));
            case FAILED -> {
                if (sale.reason().isBlank()) fail(player, "gui.sell.failed");
                else fail(player, "gui.sell.failed-reason", "reason", sale.reason());
            }
            case PENDING -> messages().send(player, "gui.sell.pending");
        }
    }

    private ItemStack sellButton(Player player, Spawner spawner) {
        if (!sellService.enabled()) {
            return button(Material.GOLD_INGOT, "&#F7B733&lSell all",
                    List.of("&#FF7B72Selling is disabled on this server."));
        }
        return button(Material.GOLD_INGOT, "&#F7B733&lSell all", List.of(
                "&7Worth: &f" + sellService.format(sellService.value(player, spawner)),
                "&7Items: &f" + MenuPlaceholders.number(sellService.sellableItems(player, spawner)),
                "",
                "&#F7B733» &7Click twice: &fsell everything"));
    }

    private void upgrade(Player player, Spawner spawner) {
        if (!permitted(player, ActionPermissions.UPGRADE)) return;
        Settings s = plugin.settings();
        int next = spawner.tier() + 1;
        if (next >= s.tiers.size()) {
            messages().send(player, "gui.upgrade.max");
            return;
        }
        Settings.Tier tier = s.tiers.get(next);
        Currency currency = CurrencyRegistry.select(plugin, CurrencyAction.UPGRADE);
        if (!currency.available()) {
            fail(player, "gui.upgrade.no-economy", currencyValues(currency));
            return;
        }
        double cost = CurrencyMath.charge(tier.cost, currency.wholeUnits());
        if (!currency.withdraw(player, cost).success()) {
            fail(player, "gui.upgrade.insufficient",
                    "cost", Messages.raw(Text.escape(currency.format(cost))), "tier", tier.name,
                    "currency", currency.displayName(), "currency_symbol", currency.symbol());
            return;
        }
        spawner.tier(next);
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.UPGRADE, player, next, tier.name);
        plugin.manager().reschedule(spawner);
        messages().send(player, "gui.upgrade.success", "tier", tier.name);
        play(player, SoundFeedback.Cue.STACKING);
        if (plugin.particles() != null) plugin.particles().burst(dev.yanianz.bravenspawner.particles.SpawnerParticles.Effect.UPGRADE, spawner.location());
        openMenu(player, spawner);
    }

    /**
     * Ends the session only when the window that closed is the current one.
     *
     * <p>A rapid close/open fires the old close event after the new holder has
     * already been recorded. Clearing by player id alone would then delete the new
     * session and release its save lock, so the holder identity is the guard.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        GuiHolder current = sessions.get(player);
        if (current == null || current.getInventory() != event.getInventory()) return;

        sessions.remove(player, current);
        // Releases the save lock and starts the reconnect cooldown so a reopen
        // cannot race the persistence pass.
        locks.markClosed(player);
    }

    private ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private void fill(Inventory inventory) {
        ItemStack pane = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane.clone());
    }

    private static Material headIcon(dev.yanianz.bravenspawner.product.ProductDefinition product) {
        return product == null ? Material.SPAWNER
                : dev.yanianz.bravenspawner.util.HeadTextures.iconMaterial(product.headMaterial(), product.headTexture());
    }

    /** The fallback menu's spawner icon: the product head when one is configured. */
    private ItemStack headButton(dev.yanianz.bravenspawner.product.ProductDefinition product, String name, List<String> lore) {
        ItemStack item = button(headIcon(product), name, lore);
        if (product != null && item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull
                && dev.yanianz.bravenspawner.util.HeadTextures.apply(skull, product.headTexture())) {
            item.setItemMeta(skull);
        }
        return item;
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            meta.lore(Text.items(new ArrayList<>(lore)));
            item.setItemMeta(meta);
        }
        return item;
    }

    public Component title(String raw) {
        return Text.of(raw);
    }
}
