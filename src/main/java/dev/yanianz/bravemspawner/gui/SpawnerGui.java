package dev.yanianz.bravemspawner.gui;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.config.Settings;
import dev.yanianz.bravemspawner.model.Spawner;
import dev.yanianz.bravemspawner.sell.SellService;
import dev.yanianz.bravemspawner.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest GUI: a paged storage view plus a control menu.
 *
 * <p>Everything is read-only except the three action buttons, so the inventory can
 * never be used to dupe or to move items the model still owns. Page navigation is
 * instant when configured, otherwise delayed by one tick.
 */
public final class SpawnerGui implements Listener {

    private enum View { MENU, STORAGE }

    private record Session(UUID spawnerId, View view, int page) {}

    private final BravemSpawner plugin;
    private final SellService sellService;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SpawnerGui(BravemSpawner plugin) {
        this.plugin = plugin;
        this.sellService = SellService.install(plugin);
    }

    public Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void openMenu(Player player, Spawner spawner) {
        Settings s = plugin.settings();
        if (!spawner.canUse(player.getUniqueId()) && !player.hasPermission("bravemspawner.admin")) {
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, Text.of("&8Spawner — " + spawner.product()));
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

        sessions.put(player.getUniqueId(), new Session(spawner.id(), View.MENU, 0));
        player.openInventory(inv);
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

        Inventory inv = Bukkit.createInventory(null, rows * 9,
                Text.of("&8Storage " + (safePage + 1) + "/" + pages));

        int start = safePage * perPage;
        for (int i = 0; i < perPage && start + i < contents.size(); i++) {
            inv.setItem(i, contents.get(start + i).clone());
        }

        int navRow = (rows - 1) * 9;
        if (safePage > 0) inv.setItem(navRow, button(Material.ARROW, "&ePrevious page", List.of()));
        if (safePage < pages - 1) inv.setItem(navRow + 8, button(Material.ARROW, "&eNext page", List.of()));
        inv.setItem(navRow + 4, button(Material.HOPPER, "&aTake all", List.of("&7Withdraw everything.")));
        inv.setItem(navRow + 3, button(Material.BARRIER, "&cBack", List.of()));

        sessions.put(player.getUniqueId(), new Session(spawner.id(), View.STORAGE, safePage));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() == event.getView().getBottomInventory()) return;

        Spawner spawner = plugin.manager().byId(session.spawnerId());
        if (spawner == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getSlot();
        if (session.view() == View.MENU) handleMenu(player, spawner, slot);
        else handleStorage(player, spawner, session.page(), slot);
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
        if (!player.hasPermission("bravemspawner.item.extract")) {
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
            case FAILED -> player.sendMessage(Text.of("&cSale failed — nothing was removed."));
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

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
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
