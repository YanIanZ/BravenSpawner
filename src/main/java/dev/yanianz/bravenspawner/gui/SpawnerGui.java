package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.menu.MenuDefinition;
import dev.yanianz.bravenspawner.menu.MenuHolder;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.menu.PurchaseMath;
import dev.yanianz.bravenspawner.menu.OrderedPuzzle;
import dev.yanianz.bravenspawner.menu.MenuRenderer;
import dev.yanianz.bravenspawner.audit.AuditEvent;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import dev.yanianz.bravenspawner.sell.SellService;
import dev.yanianz.bravenspawner.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chest GUI: a paged storage view plus a control menu.
 *
 * <p>Displayed storage is read-only; action callbacks withdraw from the model, so the inventory can
 * never be used to dupe or to move items the model still owns. Page navigation is
 * instant when configured, otherwise delayed by one tick.
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

    private static final class LogQuery {
        int type = -1;
        boolean mine;
        int limit = 64;
        long since;
    }

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

    public int reloadMenus() { return menus.reload(); }

    public void openMenu(Player player, Spawner spawner) {
        Settings s = plugin.settings();
        if (!authorized(player, spawner)) return;
        if (!spawner.canUse(player.getUniqueId()) && !player.hasPermission("bravenspawner.admin")) {
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }
        if (!locks.canOpen(player.getUniqueId(), spawner.id())) {
            player.sendMessage(Text.of("&c" + locks.denialReason(player.getUniqueId(), spawner.id())));
            return;
        }

        if (spawner.corrupted()) { openCorruption(player, spawner); return; }
        if (openConfigured(player, spawner, "spawner", 0, 1)) return;

        GuiHolder holder = new GuiHolder(spawner.id(), generations.incrementAndGet(), View.MENU, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, Text.of("&8Spawner — " + spawner.product()));
        holder.attach(inv);
        inv.setItem(10, button(Material.CHEST, "&aStorage",
                List.of("&7Stored: &f" + spawner.storedCount(),
                        "&7Slots: &f" + spawner.distinctSlots(),
                        "&7XP: &f" + spawner.storedXp())));
        inv.setItem(11, sellButton(player, spawner));
        inv.setItem(12, button(Material.EXPERIENCE_BOTTLE, "&eCollect XP",
                List.of("&7Move stored XP to you.")));
        inv.setItem(13, button(Material.HOPPER, "&bCollect items",
                List.of("&7Withdraw everything that fits.")));
        inv.setItem(14, button(Material.ANVIL, "&6Upgrade",
                List.of("&7Tier: &f" + spawner.tier(),
                        "&7Stack: &f" + spawner.stack())));
        inv.setItem(15, button(Material.NAME_TAG, "&dTrusted",
                List.of("&7/bmodify <player>")));
        inv.setItem(16, button(Material.BARRIER, "&cClose", List.of()));

        if (!show(player, inv)) return;
        sessions.put(player.getUniqueId(), holder);
        locks.markOpened(player.getUniqueId(), spawner.id());
        if (s.enableLogger) {
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
        if (!sameSpawner && !locks.canOpen(viewer, spawner.id())) {
            player.sendMessage(Text.of("&c" + locks.denialReason(viewer, spawner.id())));
            return;
        }

        GuiHolder holder = new GuiHolder(spawner.id(), generations.incrementAndGet(), View.STORAGE, safePage);
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                Text.of("&8Storage " + (safePage + 1) + "/" + pages));
        holder.attach(inv);

        int start = safePage * perPage;
        for (int i = 0; i < perPage && start + i < contents.size(); i++) {
            inv.setItem(i, contents.get(start + i).clone());
        }

        int navRow = (rows - 1) * 9;
        if (safePage > 0) inv.setItem(navRow, button(Material.ARROW, "&ePrevious page", List.of()));
        if (safePage < pages - 1) inv.setItem(navRow + 8, button(Material.ARROW, "&eNext page", List.of()));
        inv.setItem(navRow + 4, button(Material.HOPPER, "&aTake all", List.of("&7Withdraw everything.")));
        inv.setItem(navRow + 3, button(Material.BARRIER, "&cBack", List.of()));

        if (!show(player, inv)) return;
        sessions.put(viewer, holder);
        locks.markOpened(viewer, spawner.id());
    }

    private boolean authorized(Player player, Spawner spawner) {
        if (player != null && spawner != null) {
            if (plugin.restrictions() != null && !plugin.restrictions().canOpen(player, spawner)) return false;
            if (plugin.protection() != null && !plugin.protection().canUse(player, spawner.blockLocation())) return false;
        }
        if (spawner.canUse(player.getUniqueId()) || player.hasPermission("bravenspawner.admin")) return true;
        player.sendMessage(Text.of("&cThat spawner is not yours."));
        return false;
    }

    private Map<String, String> placeholders(Player player, Spawner spawner, int page, int pages) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("entity", spawner.product());
        values.put("tier", String.valueOf(spawner.tier()));
        values.put("stack", String.valueOf(spawner.stack()));
        values.put("stored", String.valueOf(spawner.storedCount()));
        values.put("xp", String.valueOf(spawner.storedXp()));
        values.put("value", sellService.format(sellService.value(player, spawner)));
        values.put("page", String.valueOf(page + 1));
        values.put("pages", String.valueOf(pages));
        values.put("head_texture", "");
        var product = plugin.product(spawner);
        double price = product == null ? 0.0 : product.spawnerPrice();
        values.put("max_stack", String.valueOf(plugin.stackLimit(spawner)));
        values.put("price", plugin.economy().format(price));
        values.put("total_price", plugin.economy().format(price * 64));
        values.put("player", "All");
        values.put("logtype", "All");
        values.put("limit", "64");
        values.put("time", "All time");
        return values;
    }

    private boolean openConfigured(Player player, Spawner spawner, String name, int page, int pages) {
        MenuDefinition definition = menus.definition(name);
        if (definition == null) return false;
        UUID viewer = player.getUniqueId();
        if (!authorized(player, spawner)) return true;
        if (!spawner.id().equals(locks.sessionSpawner(viewer)) && !locks.canOpen(viewer, spawner.id())) return true;
        Map<Character, Consumer<Player>> actions = new LinkedHashMap<>();
        for (var entry : definition.items().entrySet()) {
            String action = entry.getValue().action();
            actions.put(entry.getKey(), clicker -> {
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
                configuredAction(clicker, live, name, action, page);
            });
        }
        String layout = name.equals("spawner") && plugin.platform() != null
                && plugin.platform().configured() && plugin.platform().isBedrock(player)
                ? "bedrock_mode" : definition.defaultLayout();
        MenuHolder opened = menus.open(player, name, layout, placeholders(player, spawner, page, pages), actions);
        if (opened == null) return true;
        GuiHolder session = new GuiHolder(spawner.id(), generations.incrementAndGet(),
                name.equals("storage") ? View.STORAGE : name.equals("corruption") ? View.PUZZLE
                        : name.equals("log") ? View.LOG : View.MENU, page);
        session.attach(opened.getInventory());
        sessions.put(viewer, session);
        locks.markOpened(viewer, spawner.id());
        return true;
    }

    /** Content cells are filler cells above the last control row. Controls may be moved freely. */
    private List<Integer> storageSlots(MenuDefinition definition) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < (definition.rows() - 1) * 9; slot++) {
            char symbol = definition.symbol(definition.defaultLayout(), slot);
            if (symbol == 0 || symbol == definition.filler()) slots.add(slot);
        }
        return slots;
    }

    private boolean openConfiguredStorage(Player player, Spawner spawner, int requested) {
        MenuDefinition definition = menus.definition("storage");
        if (definition == null) return false;
        List<Integer> slots = storageSlots(definition);
        if (slots.isEmpty()) return false;
        List<ItemStack> contents = spawner.contents();
        int pages = Math.max(1, (contents.size() + slots.size() - 1) / slots.size());
        int page = Math.max(0, Math.min(requested, pages - 1));
        MenuHolder previous = menus.session(player);
        if (!openConfigured(player, spawner, "storage", page, pages)) return false;
        if (menus.session(player) == previous) return true;
        GuiHolder current = session(player);
        if (current == null || !current.spawnerId().equals(spawner.id()) || current.page() != page) return true;
        current.contentSlots = List.copyOf(slots);
        current.displayed = contents.stream().map(ItemStack::clone).toList();
        for (int i = 0; i < slots.size(); i++) {
            int index = page * slots.size() + i;
            ItemStack display = index < contents.size() ? contents.get(index).clone() : null;
            if (display != null) display.setAmount(Math.min(display.getAmount(), display.getMaxStackSize()));
            current.getInventory().setItem(slots.get(i), display);
        }
        return true;
    }

    public void openLog(Player player, Spawner spawner, int requestedPage) {
        if (!player.hasPermission("bravenspawner.admin")) {
            player.sendMessage(Text.of("&cYou cannot view spawner audit logs."));
            return;
        }
        MenuDefinition definition = menus.definition("log");
        if (definition == null || plugin.audit() == null) return;
        List<Integer> slots = storageSlots(definition);
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
        for (int cell = 0; cell < slots.size(); cell++) {
            int index = page * slots.size() + cell;
            if (index >= events.size()) { current.getInventory().setItem(slots.get(cell), null); continue; }
            AuditEvent event = events.get(index);
            current.getInventory().setItem(slots.get(cell), button(Material.PAPER,
                    "&e" + event.action(), List.of("&7" + java.time.Instant.ofEpochMilli(event.timestamp()),
                            "&7Player: &f" + Text.escape(event.actorName()), "&7Amount: &f" + event.amount(),
                            "&7" + event.world() + " " + event.x() + ", " + event.y() + ", " + event.z(),
                            "&f" + Text.escape(event.detail()))));
        }
        Map<String, String> values = new LinkedHashMap<>(placeholders(player, spawner, page, pages));
        values.put("player", query.mine ? Text.escape(player.getName()) : "All");
        values.put("logtype", query.type < 0 ? "All" : AuditEvent.Action.values()[query.type].name());
        values.put("limit", String.valueOf(query.limit));
        values.put("time", query.since == 0 ? "All time" : query.since == 3_600_000 ? "1 hour" : "24 hours");
        for (int slot = 0; slot < definition.rows() * 9; slot++) {
            char symbol = definition.symbol(definition.defaultLayout(), slot);
            if (symbol != definition.filler() && symbol != 0) {
                current.getInventory().setItem(slot, menuItem(player, definition.item(symbol), values));
            }
        }
    }

    private void updateLogQuery(Player player, Spawner spawner, String action) {
        if (!player.hasPermission("bravenspawner.admin")) return;
        GuiHolder current = session(player);
        if (current == null || current.query == null) return;
        LogQuery query = current.query;
        switch (action) {
            case "target" -> query.mine = !query.mine;
            case "logtype" -> query.type = query.type + 1 >= AuditEvent.Action.values().length ? -1 : query.type + 1;
            case "limit" -> query.limit = query.limit == 64 ? 16 : query.limit == 16 ? 32 : 64;
            case "time" -> query.since = query.since == 0 ? 3_600_000 : query.since == 3_600_000 ? 86_400_000 : 0;
            default -> { }
        }
        openLog(player, spawner, 0);
    }

    private void openCorruption(Player player, Spawner spawner) {
        MenuDefinition definition = menus.definition("corruption");
        if (definition == null || definition.puzzleSlots().isEmpty()) {
            player.sendMessage(Text.of("&cThe corruption puzzle is unavailable; ask an administrator to check gui/corruption.yml."));
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
                if (!message.isBlank()) player.sendMessage(Text.of(message));
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
                if (!message.isBlank()) player.sendMessage(Text.of(message));
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
        if (!authorized(player, spawner)) return;
        var product = plugin.product(spawner);
        double price = product == null ? 0.0 : product.spawnerPrice();
        if (!Double.isFinite(price) || price <= 0 || !plugin.economy().enabled()) {
            player.sendMessage(Text.of("&cPurchasing this spawner is unavailable."));
            return;
        }
        var world = Bukkit.getWorld(spawner.world());
        if (world == null) return;
        int chunkRoom = Integer.MAX_VALUE;
        if (plugin.settings().spawnerLimitInChunk > 0) {
            chunkRoom = Math.max(0, plugin.settings().spawnerLimitInChunk
                    - plugin.manager().stackedInChunk(world, spawner.x() >> 4, spawner.z() >> 4));
        }
        int limit = plugin.stackLimit(spawner);
        int count = PurchaseMath.quantity(requested, spawner.roomFor(limit), chunkRoom,
                plugin.economy().balance(player), price);
        if (count <= 0) {
            player.sendMessage(Text.of("&cNo room or insufficient funds for this purchase."));
            return;
        }
        double total = count * price;
        if (!Double.isFinite(total) || !plugin.economy().withdraw(player, total)) {
            player.sendMessage(Text.of("&cPayment failed; no spawners were added."));
            return;
        }
        if (!spawner.tryAddStack(count, limit)) {
            if (!plugin.economy().deposit(player, total)) {
                plugin.getLogger().severe("Failed to refund spawner purchase for " + player.getUniqueId() + ": " + total);
            }
            player.sendMessage(Text.of("&cThe spawner filled before the purchase; a refund was requested."));
            return;
        }
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.STACK, player, count, "buy_stack: " + total);
        plugin.manager().reschedule(spawner);
        player.sendMessage(Text.of("&aPurchased &f" + count + " &aspawner(s) for &f" + plugin.economy().format(total)));
        openBuyStack(player, spawner);
    }

    private void configuredAction(Player player, Spawner spawner, String menu, String action, int page) {
        switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "storage" -> openStorage(player, spawner, 0);
            case "xp", "collect_xp" -> { collectXp(player, spawner); plugin.markDirty(spawner); openMenu(player, spawner); }
            case "takeall", "take_all", "collect_items" -> { collectAll(player, spawner); plugin.markDirty(spawner); openStorage(player, spawner, page); }
            case "sellall", "sell_all" -> sellAll(player, spawner);
            case "previous" -> { if (menu.equals("log")) openLog(player, spawner, page - 1); else openStorage(player, spawner, page - 1); }
            case "next" -> { if (menu.equals("log")) openLog(player, spawner, page + 1); else openStorage(player, spawner, page + 1); }
            case "goback", "back" -> openMenu(player, spawner);
            case "close" -> player.closeInventory();
            case "upgrade" -> upgrade(player, spawner);
            case "trusted" -> player.sendMessage(Text.of("&7/bmodify <player> while looking at the spawner"));
            case "buy_stack", "information" -> openBuyStack(player, spawner);
            case "log" -> openLog(player, spawner, 0);
            case "target", "logtype", "limit", "time" -> {
                if (menu.equals("log")) updateLogQuery(player, spawner, action);
            }
            case "buy_1" -> buyStack(player, spawner, 1);
            case "buy_64" -> buyStack(player, spawner, 64);
            case "buy_max" -> buyStack(player, spawner, Integer.MAX_VALUE);
            case "boosters" -> player.sendMessage(Text.of("&7Apply a booster item to this spawner to activate it."));
            default -> { }
        }
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

        if (plugin.manager() == null) {
            player.closeInventory();
            return;
        }
        Spawner spawner = plugin.manager().byId(current.spawnerId());
        if (spawner == null) {
            player.closeInventory();
            return;
        }
        if (!authorized(player, spawner)) { player.closeInventory(); return; }
        int slot = event.getSlot();
        if (current.menu()) handleMenu(player, spawner, slot);
        else handleStorage(player, spawner, current.page(), slot);
    }

    private void onConfiguredDataClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        GuiHolder current = session(player);
        Inventory top = event.getView().getTopInventory();
        if (current == null || current.getInventory() != top
                || event.getClickedInventory() != top) return;
        if (current.view == View.PUZZLE) {
            Spawner spawner = plugin.manager() == null ? null : plugin.manager().byId(current.spawnerId());
            if (spawner == null || !authorized(player, spawner)
                    || !spawner.id().equals(locks.sessionSpawner(player.getUniqueId()))) {
                player.closeInventory();
                return;
            }
            puzzleClick(player, spawner, current, event.getSlot());
            return;
        }
        if (current.view != View.STORAGE) return;
        int cell = current.contentSlots.indexOf(event.getSlot());
        if (cell < 0) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                && event.getClick() != org.bukkit.event.inventory.ClickType.SHIFT_LEFT) return;
        Spawner spawner = plugin.manager() == null ? null : plugin.manager().byId(current.spawnerId());
        if (spawner == null || !authorized(player, spawner)
                || !spawner.id().equals(locks.sessionSpawner(player.getUniqueId()))) {
            player.closeInventory();
            return;
        }
        if (spawner.corrupted()) { openCorruption(player, spawner); return; }
        if (!player.hasPermission("bravenspawner.item.extract")) {
            player.sendMessage(Text.of("&cYou cannot withdraw from spawners."));
            return;
        }
        if (plugin.restrictions() != null && !plugin.restrictions().canTakeItems(player, spawner)) return;
        int index = current.page() * current.contentSlots.size() + cell;
        if (index >= current.displayed.size()) return;
        ItemStack expected = current.displayed.get(index);
        int amount = event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                ? Math.min(expected.getAmount(), expected.getMaxStackSize()) : 1;
        ItemStack withdrawn = spawner.drainSlotIfSimilar(index, expected, amount);
        if (withdrawn != null) {
            int original = withdrawn.getAmount();
            Map<Integer, ItemStack> remainder = player.getInventory().addItem(withdrawn);
            remainder.values().forEach(item -> spawner.storeVirtual(item, 0));
            plugin.markDirty(spawner);
            long transferred = original - remainder.values().stream().mapToLong(ItemStack::getAmount).sum();
            if (transferred > 0) plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, transferred, "storage cell");
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

    private void handleMenu(Player player, Spawner spawner, int slot) {
        switch (slot) {
            case 10 -> openStorage(player, spawner, 0);
            case 11 -> sellAll(player, spawner);
            case 12 -> collectXp(player, spawner);
            case 13 -> collectAll(player, spawner);
            case 14 -> upgrade(player, spawner);
            case 15 -> player.sendMessage(Text.of("&7/bmodify <player> while looking at the spawner"));
            case 16 -> player.closeInventory();
            default -> { }
        }
    }

    private void handleStorage(Player player, Spawner spawner, int page, int slot) {
        int navRow = 45;
        if (slot == navRow) {
            openStorage(player, spawner, page - 1);
            return;
        }
        if (slot == navRow + 8) {
            openStorage(player, spawner, page + 1);
            return;
        }
        if (slot == navRow + 4) {
            collectAll(player, spawner);
            return;
        }
        if (slot == navRow + 3) {
            openMenu(player, spawner);
        }
    }

    private void collectXp(Player player, Spawner spawner) {
        synchronized (spawner) {
        var product = plugin.product(spawner);
        double eventMultiplier = plugin.events() == null ? 1.0 : plugin.events().xpMultiplier();
        double personalMultiplier = plugin.boosters() == null ? 1.0
                : plugin.boosters().personalMultiplier(dev.yanianz.bravenspawner.boosters.Boosters.Type.XP, player.getUniqueId());
        long payout = Math.min(Integer.MAX_VALUE, Math.round(Math.round(spawner.storedXp() * eventMultiplier) * personalMultiplier));
        long full = Math.min(Integer.MAX_VALUE, Math.round(plugin.maximumXp(spawner) * eventMultiplier));
        if (product != null && product.mustBeFullToCollect() && payout < full) {
            player.sendMessage(Text.of("&cXP storage must be full before collecting."));
            return;
        }
        if (payout <= 0) {
            player.sendMessage(Text.of("&7No XP stored."));
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
        player.sendMessage(Text.of("&aCollected &f" + xp + " &aXP."));
        }
    }

    private void collectAll(Player player, Spawner spawner) {
        if (plugin.restrictions() != null && !plugin.restrictions().canTakeItems(player, spawner)) return;
        if (!player.hasPermission("bravenspawner.item.extract")) {
            player.sendMessage(Text.of("&cYou cannot withdraw from spawners."));
            return;
        }
        List<ItemStack> items = spawner.drainAll();
        if (items.isEmpty()) {
            player.sendMessage(Text.of("&7Nothing stored."));
            return;
        }
        long before = items.stream().mapToLong(ItemStack::getAmount).sum();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        leftover.values().forEach(item -> spawner.storeVirtual(item, 0));
        plugin.markDirty(spawner);
        long withdrawn = before - leftover.values().stream().mapToLong(ItemStack::getAmount).sum();
        if (withdrawn > 0) plugin.record(spawner, AuditEvent.Action.WITHDRAW, player, withdrawn, "take all");
        player.sendMessage(Text.of("&aWithdrew &f" + withdrawn + " &aitem(s)."));
    }

    private void sellAll(Player player, Spawner spawner) {
        var product = plugin.product(spawner);
        if (product != null && product.mustBeFullToCollect()
                && spawner.storedCount() < plugin.maximumItems(spawner)) {
            player.sendMessage(Text.of("&cItem storage must be full before selling."));
            return;
        }
        SellService.Sale sale = sellService.sell(player, spawner);
        switch (sale.status()) {
            case SOLD -> player.sendMessage(Text.of("&aSold &f" + sale.items()
                    + " &aitem(s) for &f" + sellService.format(sale.value()) + "&a."));
            case NOTHING_TO_SELL -> player.sendMessage(Text.of("&7Nothing stored with a sell price."));
            case DISABLED -> player.sendMessage(Text.of("&cSelling is disabled on this server."));
            case NO_ECONOMY -> player.sendMessage(Text.of("&cEconomy not available — nothing was sold."));
            case FAILED -> player.sendMessage(Text.of("&cSale failed — nothing was removed."
                    + (sale.reason().isBlank() ? "" : " &7(" + sale.reason() + ")")));
            case PENDING -> player.sendMessage(Text.of("&eA previous payout is still being settled; "
                    + "no new sale was made."));
        }
        openMenu(player, spawner);
    }

    private ItemStack sellButton(Player player, Spawner spawner) {
        if (!sellService.enabled()) {
            return button(Material.GOLD_INGOT, "&6Sell all",
                    List.of("&cSelling is disabled on this server."));
        }
        return button(Material.GOLD_INGOT, "&6Sell all", List.of(
                "&7Value: &f" + sellService.format(sellService.value(player, spawner)),
                "&7Items: &f" + sellService.sellableItems(player, spawner),
                "&7Sells every priced stack in storage.",
                "&7Click to sell."));
    }

    private void upgrade(Player player, Spawner spawner) {
        Settings s = plugin.settings();
        int next = spawner.tier() + 1;
        if (next >= s.tiers.size()) {
            player.sendMessage(Text.of("&7Already at the highest tier."));
            return;
        }
        Settings.Tier tier = s.tiers.get(next);
        if (!plugin.economy().enabled()) {
            player.sendMessage(Text.of("&cEconomy not available."));
            return;
        }
        if (!plugin.economy().withdraw(player, tier.cost)) {
            player.sendMessage(Text.of("&cNeed &f" + plugin.economy().format(tier.cost) + " &cfor " + tier.name));
            return;
        }
        spawner.tier(next);
        plugin.markDirty(spawner);
        plugin.record(spawner, AuditEvent.Action.UPGRADE, player, next, tier.name);
        plugin.manager().reschedule(spawner);
        player.sendMessage(Text.of("&aUpgraded to &f" + tier.name));
        openMenu(player, spawner);
    }

    private int capacityFor(Spawner spawner) {
        Settings s = plugin.settings();
        if (s.unlimitedStorage) return 0;
        return s.tiers.get(Math.min(spawner.tier(), s.tiers.size() - 1)).capacity;
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
