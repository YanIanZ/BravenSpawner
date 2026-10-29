package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
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
 * <p>Everything is read-only except the three action buttons, so the inventory can
 * never be used to dupe or to move items the model still owns. Page navigation is
 * instant when configured, otherwise delayed by one tick.
 *
 * <p>Sessions are identified by the window itself, not by the player: every open
 * inventory carries a typed {@link GuiHolder} with the spawner id and a monotonic
 * generation, and an event is only honoured when its holder is still the current
 * session. Titles are never parsed, so a rapid close/open cannot let the close of
 * an old window clear the session of the new one.
 */
public final class SpawnerGui implements Listener {

    private enum View { MENU, STORAGE }

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
    private final AtomicLong generations = new AtomicLong();
    private final Map<UUID, GuiHolder> sessions = new ConcurrentHashMap<>();

    public SpawnerGui(BravenSpawner plugin) {
        this.plugin = plugin;
        this.locks = GuiLock.of(plugin);
        this.sellService = SellService.install(plugin);
    }

    /** The window the player currently owns, or null when they have no spawner GUI open. */
    public GuiHolder session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public void openMenu(Player player, Spawner spawner) {
        Settings s = plugin.settings();
        if (!spawner.canUse(player.getUniqueId()) && !player.hasPermission("bravenspawner.admin")) {
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }
        if (!locks.canOpen(player.getUniqueId(), spawner.id())) {
            player.sendMessage(Text.of("&c" + locks.denialReason(player.getUniqueId(), spawner.id())));
            return;
        }

        GuiHolder holder = new GuiHolder(spawner.id(), generations.incrementAndGet(), View.MENU, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, Text.of("&8Spawner — " + spawner.product()));
        holder.attach(inv);
        inv.setItem(10, button(Material.CHEST, "&aStorage",
                List.of("&7Stored: &f" + spawner.storedCount(),
                        "&7Slots: &f" + spawner.distinctSlots(),
                        "&7XP: &f" + spawner.storedXp())));
        inv.setItem(11, sellButton(spawner));
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
        int slot = event.getSlot();
        if (current.menu()) handleMenu(player, spawner, slot);
        else handleStorage(player, spawner, current.page(), slot);
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
        int xp = spawner.takeXp();
        if (xp <= 0) {
            player.sendMessage(Text.of("&7No XP stored."));
            return;
        }
        if (plugin.settings().dropXpAsOrbs) {
            var loc = spawner.location();
            if (loc != null) loc.getWorld().spawn(loc, org.bukkit.entity.ExperienceOrb.class,
                    orb -> orb.setExperience(xp));
        } else {
            player.giveExp(xp);
        }
        player.sendMessage(Text.of("&aCollected &f" + xp + " &aXP."));
    }

    private void collectAll(Player player, Spawner spawner) {
        if (!player.hasPermission("bravenspawner.item.extract")) {
            player.sendMessage(Text.of("&cYou cannot withdraw from spawners."));
            return;
        }
        List<ItemStack> items = spawner.drainAll();
        if (items.isEmpty()) {
            player.sendMessage(Text.of("&7Nothing stored."));
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        int capacity = capacityFor(spawner);
        leftover.values().forEach(item -> spawner.store(item, capacity));
        player.sendMessage(Text.of("&aWithdrew &f" + (items.size() - leftover.size()) + " &astack(s)."));
    }

    private void sellAll(Player player, Spawner spawner) {
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

    private ItemStack sellButton(Spawner spawner) {
        if (!sellService.enabled()) {
            return button(Material.GOLD_INGOT, "&6Sell all",
                    List.of("&cSelling is disabled on this server."));
        }
        return button(Material.GOLD_INGOT, "&6Sell all", List.of(
                "&7Value: &f" + sellService.format(sellService.value(spawner)),
                "&7Items: &f" + sellService.sellableItems(spawner),
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
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        UUID player = event.getPlayer().getUniqueId();
        GuiHolder current = sessions.get(player);
        if (current == null || current.generation() != holder.generation()) return;

        sessions.remove(player, holder);
        // Releases the save lock and starts the reconnect cooldown so a reopen
        // cannot race the persistence pass.
        locks.markClosed(player);
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            meta.lore(Text.of(new ArrayList<>(lore)));
            item.setItemMeta(meta);
        }
        return item;
    }

    public Component title(String raw) {
        return Text.of(raw);
    }
}
