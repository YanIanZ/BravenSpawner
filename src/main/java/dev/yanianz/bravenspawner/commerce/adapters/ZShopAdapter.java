package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * ZShop (GroupeZ / Maxlego08) sell quotes, read by reflection.
 *
 * <p>API verified against Maxlego08/zShop-API ({@code fr.maxlego08.zshop.api}):
 * {@code ShopManager#getItemButton(Material)} and
 * {@code getItemButton(Player, ItemStack)} return {@code Optional<ItemButton>};
 * {@code ItemButton#getSellPrice(Player, int)}, {@code getSellPrice(int)} and
 * {@code getSellPrice()} quote it. The reference plugin's bridge calls
 * {@code ShopPlugin#getShopManager()} then {@code getItemButton(Material)} and
 * {@code getSellPrice()}, which is the fallback path here.
 */
public final class ZShopAdapter extends AbstractPluginShopAdapter {

    private record Access(Plugin owner, Method manager, Method byMaterial, Method byStack,
                          Method sellForPlayer, Method sellForAmount, Method sell) {}

    private volatile Access access;

    public ZShopAdapter(Logger logger) {
        super(logger, "ZShop", "ZShop's ShopManager/ItemButton API (fr.maxlego08.zshop.api)",
                "zShop", "ZShop");
    }

    private Access access() {
        Plugin owner = plugin();
        if (owner == null || !owner.isEnabled()) return null;
        Access cached = access;
        if (cached != null && cached.owner() == owner) return cached;
        try {
            Method manager = Reflection.method(owner.getClass(), "getShopManager");
            Class<?> managerType = Reflection.load(owner, "fr.maxlego08.zshop.api.ShopManager");
            Class<?> buttonType = Reflection.load(owner, "fr.maxlego08.zshop.api.buttons.ItemButton");
            Access built = new Access(owner, manager,
                    Reflection.method(managerType, "getItemButton", Material.class),
                    Reflection.optional(managerType, "getItemButton", Player.class, ItemStack.class),
                    Reflection.optional(buttonType, "getSellPrice", Player.class, int.class),
                    Reflection.optional(buttonType, "getSellPrice", int.class),
                    Reflection.method(buttonType, "getSellPrice"));
            access = built;
            return built;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            reportFailure(error);
            return null;
        }
    }

    @Override
    protected boolean apiPresent() {
        Access current = access();
        return current != null && Reflection.invoke(current.manager(), current.owner()) != null;
    }

    @Override
    public double priceOf(ItemStack stack) {
        return priceOf(null, stack);
    }

    @Override
    public double priceOf(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        Access current = access();
        if (current == null) return -1.0;
        try {
            Object manager = Reflection.invoke(current.manager(), current.owner());
            if (manager == null) return -1.0;
            Object found = player != null && current.byStack() != null
                    ? Reflection.invoke(current.byStack(), manager, player, stack)
                    : Reflection.invoke(current.byMaterial(), manager, stack.getType());
            if (!(found instanceof Optional<?> optional) || optional.isEmpty()) return -1.0;
            Object button = optional.get();
            double price;
            if (player != null && current.sellForPlayer() != null) {
                price = Reflection.asDouble(Reflection.invoke(current.sellForPlayer(), button, player, 1));
            } else if (current.sellForAmount() != null) {
                price = Reflection.asDouble(Reflection.invoke(current.sellForAmount(), button, 1));
            } else {
                price = Reflection.asDouble(Reflection.invoke(current.sell(), button));
            }
            return Double.isFinite(price) && price > 0.0 ? price : -1.0;
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }
}
