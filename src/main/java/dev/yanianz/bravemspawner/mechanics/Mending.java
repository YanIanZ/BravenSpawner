package dev.yanianz.bravemspawner.mechanics;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.model.Spawner;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * Turns stored spawner XP into Mending repairs when the spawner is broken.
 *
 * <p>Breaking a spawner already consumes the block and drops its contents; the
 * banked XP would otherwise disappear. When the breaking tool has Mending and the
 * feature is enabled, one XP point repairs two durability, matching vanilla
 * Mending. Excess XP is returned to the spawner after the repair is capped at the
 * tool's maximum durability, so a nearly full tool does not burn the whole bank.
 */
public final class Mending {

    private Mending() {
    }

    /**
     * Applies stored XP to the player's main-hand tool.
     *
     * @return true when at least one point of durability was restored
     */
    public static boolean repair(BravemSpawner plugin, Player player, Spawner spawner) {
        if (plugin == null || player == null || spawner == null) return false;
        if (!plugin.getConfig().getBoolean("enable-mending", false)) return false;
        if (player.getGameMode() == GameMode.CREATIVE) return false;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return false;
        if (!hand.containsEnchantment(Enchantment.MENDING)) return false;
        if (!(hand.getItemMeta() instanceof Damageable damageable)) return false;

        int damage = damageable.getDamage();
        int maxDamage = damageable.getMaxDamage();
        if (damage <= 0 || maxDamage <= 0) return false;

        int missing = maxDamage - damage;
        if (missing <= 0) return false;

        int stored = spawner.takeXp();
        if (stored <= 0) return false;

        int usefulXp = (missing + 1) / 2;
        int used = Math.min(stored, usefulXp);
        int repaired = Math.min(missing, used * 2);

        damageable.setDamage(damage - repaired);
        hand.setItemMeta(damageable);

        int leftover = stored - used;
        if (leftover > 0) spawner.addXp(leftover);
        return repaired > 0;
    }
}
