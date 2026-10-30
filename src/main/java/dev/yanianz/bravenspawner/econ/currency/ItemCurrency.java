package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.CurrencyMath;
import dev.yanianz.bravenspawner.econ.ItemCurrencyMath;
import dev.yanianz.bravenspawner.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

/**
 * Physical items as currency ({@code economy.item-currency}).
 *
 * <p>Balance is the number of matching items in the player's storage slots
 * (not armour or off-hand). Withdrawals remove exactly the price or nothing;
 * deposits fill the inventory and drop any overflow at the player's feet.
 */
public final class ItemCurrency extends OnlineCurrency {

    public static final String ID = "ITEM";

    /** What counts as one unit. Empty name and {@code customModelData <= 0} match any. */
    public record Spec(Material material, String name, int customModelData) {
        public static final Spec DEFAULT = new Spec(Material.EMERALD, "", 0);

        public static Spec read(ConfigurationSection section) {
            if (section == null) return DEFAULT;
            Material material = Material.matchMaterial(section.getString("material", "EMERALD"));
            if (material == null || material.isAir() || !material.isItem()) material = Material.EMERALD;
            return new Spec(material, section.getString("name", ""), section.getInt("custom-model-data", 0));
        }
    }

    private final CurrencyFormats formats;

    public ItemCurrency(CurrencyFormats formats) {
        super(ID, "Items", CurrencyFormat.of("", "", 0), formats);
        this.formats = formats;
    }

    public Spec spec() {
        var economy = formats == null ? null : formats.economy();
        return Spec.read(economy == null ? null : economy.getConfigurationSection("item-currency"));
    }

    @Override
    public String displayName() {
        String configured = super.displayName();
        if (!configured.equals("Items")) return configured;
        Spec spec = spec();
        if (!spec.name().isBlank()) return Text.strip(spec.name());
        return pretty(spec.material());
    }

    @Override
    protected CurrencyFormat formatSpec() {
        CurrencyFormat base = super.formatSpec();
        if (base.symbol().isEmpty() && base.suffix().isEmpty()) {
            return new CurrencyFormat("", " " + displayName(), base.decimals(), base.compact());
        }
        return base;
    }

    private static String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    boolean matches(ItemStack stack, Spec spec) {
        if (stack == null || stack.getType() != spec.material() || stack.getAmount() <= 0) return false;
        boolean wantsName = !spec.name().isBlank();
        boolean wantsModel = spec.customModelData() > 0;
        if (!wantsName && !wantsModel) return true;
        if (!stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (wantsName) {
            if (!meta.hasDisplayName() || meta.displayName() == null) return false;
            String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            if (!plain.equals(Text.strip(spec.name()))) return false;
        }
        if (wantsModel) {
            if (!meta.hasCustomModelData() || meta.getCustomModelData() != spec.customModelData()) return false;
        }
        return true;
    }

    private int[] matching(ItemStack[] contents, Spec spec) {
        int[] amounts = new int[contents.length];
        for (int i = 0; i < contents.length; i++) {
            if (matches(contents[i], spec)) amounts[i] = contents[i].getAmount();
        }
        return amounts;
    }

    @Override
    double balanceOf(Player player) {
        return ItemCurrencyMath.balance(matching(player.getInventory().getStorageContents(), spec()));
    }

    @Override
    Result takeFrom(Player player, double amount) {
        int units = CurrencyMath.toWholeUnits(Math.ceil(amount - 1e-9));
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int[] plan = ItemCurrencyMath.plan(matching(contents, spec()), units);
        if (plan == null) return Result.fail("not enough " + displayName());
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            ItemStack stack = contents[i];
            int left = stack.getAmount() - plan[i];
            if (left <= 0) contents[i] = null;
            else stack.setAmount(left);
        }
        inventory.setStorageContents(contents);
        return Result.ok(units);
    }

    @Override
    Result giveTo(Player player, double amount) {
        int units = CurrencyMath.toWholeUnits(amount);
        if (units <= 0) return Result.fail("amount is below one item");
        Spec spec = spec();
        ItemStack template = new ItemStack(spec.material());
        if (!spec.name().isBlank() || spec.customModelData() > 0) {
            ItemMeta meta = template.getItemMeta();
            if (meta != null) {
                if (!spec.name().isBlank()) meta.displayName(Text.item(spec.name()));
                if (spec.customModelData() > 0) meta.setCustomModelData(spec.customModelData());
                template.setItemMeta(meta);
            }
        }
        for (int size : ItemCurrencyMath.split(units, template.getMaxStackSize())) {
            ItemStack stack = template.clone();
            stack.setAmount(size);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack rest : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        return Result.ok(units);
    }
}
