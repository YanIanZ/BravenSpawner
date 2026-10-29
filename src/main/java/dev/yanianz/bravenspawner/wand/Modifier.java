package dev.yanianz.bravenspawner.wand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One concrete modifier value attached to one wand.
 *
 * <p>Immutable on purpose: a wand is an item, and an item can be copied by the
 * client, a chest, or a shulker. The value that is rendered, stored and used must
 * be the same value, so there is no setter and no shared mutable template.
 *
 * <p>Construction clamps to the type's {@code max_value}. A modifier item produced
 * by an older config, a command or a hand edit therefore cannot push a wand past
 * the limit the admin declared, and a non-finite value falls back to the type's
 * documented default instead of writing {@code NaN} into the item's PDC.
 */
public final class Modifier {

    private final ModifierType type;
    private final double value;

    private Modifier(ModifierType type, double value) {
        this.type = type;
        this.value = value;
    }

    /**
     * Canonical factory: the only way to build a modifier.
     *
     * <p>A price multiplier is floored at one so an upgrade never reduces the
     * authoritative quote. A flat stat is floored at zero.
     */
    public static Modifier of(ModifierType type, double value) {
        Objects.requireNonNull(type, "type");
        double safe = Double.isFinite(value) ? value : type.defaultValue();
        double floor = type.effect() == ModifierType.Effect.PRICE_MULTIPLIER ? 1.0 : 0.0;
        safe = Math.max(floor, Math.min(type.maxValue(), safe));
        return new Modifier(type, safe);
    }

    /** The value the modifier would get when none is supplied. */
    public static Modifier defaultOf(ModifierType type) {
        return of(type, type.defaultValue());
    }

    public ModifierType type() { return type; }

    public double value() { return value; }

    /** Stable effect key written into the item's PDC ({@code range}, {@code multiplier}). */
    public String storageKey() { return type.effect().storageKey(); }

    /**
     * Percent increase for a price multiplier ({@code 1.5 → 50}); for a flat stat
     * such as range it falls through to the raw value, since a percentage of an
     * additive block count has no meaning.
     */
    public double percentage() {
        return type.effect() == ModifierType.Effect.PRICE_MULTIPLIER ? (value - 1.0) * 100.0 : value;
    }

    public String displayName() {
        return render(type.nameTemplate());
    }

    public String description() {
        return render(type.descriptionTemplate());
    }

    /** The line inserted into the wand's lore when this modifier is applied. */
    public String appliedLore() {
        return render(type.appliedLoreTemplate());
    }

    /** The modifier item's own lore; placeholders are rendered from this value. */
    public List<String> itemLore() {
        List<String> out = new ArrayList<>(type.loreTemplate().size());
        for (String line : type.loreTemplate()) out.add(render(line));
        return out;
    }

    private String render(String template) {
        if (template == null) return "";
        return template
                .replace("%value%", format(value))
                .replace("%percentage%", format(percentage()));
    }

    /** Trims trailing zeroes so lore reads {@code +5} rather than {@code +5.0}. */
    public static String format(double amount) {
        if (!Double.isFinite(amount)) return "0";
        if (amount == Math.rint(amount) && Math.abs(amount) < 1.0e15) {
            return String.valueOf((long) amount);
        }
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Modifier modifier)) return false;
        return Double.compare(modifier.value, value) == 0 && type.effect() == modifier.type.effect();
    }

    @Override
    public int hashCode() {
        return Objects.hash(type.effect(), value);
    }

    @Override
    public String toString() {
        return storageKey() + '=' + value;
    }
}
