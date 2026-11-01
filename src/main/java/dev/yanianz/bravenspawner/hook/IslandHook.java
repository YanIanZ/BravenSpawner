package dev.yanianz.bravenspawner.hook;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Island-plugin view used by protection, production and autosell, free of any
 * island-plugin types so the plugin loads without one installed.
 */
public interface IslandHook {

    enum Action { USE, PLACE, BREAK }

    /** NOT_ISLAND: the location belongs to no island, so the island plugin has no say. */
    enum Verdict { NOT_ISLAND, ALLOW, DENY }

    IslandHook NONE = new IslandHook() {
        @Override public String name() { return "none"; }
        @Override public Verdict check(Player player, Location location, Action action) { return Verdict.NOT_ISLAND; }
        @Override public boolean member(UUID player, Location location) { return true; }
        @Override public double spawnRateMultiplier(Location location) { return 1.0; }
        @Override public double dropMultiplier(Location location) { return 1.0; }
        @Override public boolean sharesSpawners() { return false; }
        @Override public boolean requiresMembershipForAutosell() { return false; }
    };

    /** {@code hook}, or {@link #NONE} when absent. */
    static IslandHook of(IslandHook hook) {
        return hook == null ? NONE : hook;
    }

    String name();

    /** The island's decision for {@code player} acting at {@code location}. */
    Verdict check(Player player, Location location, Action action);

    /** True when {@code player} is a member of the island at {@code location}, or there is none. */
    boolean member(UUID player, Location location);

    /** Island upgrade multiplier for spawn speed; 1.0 off an island or when disabled. */
    double spawnRateMultiplier(Location location);

    /** Island upgrade multiplier for loot; 1.0 off an island or when disabled. */
    double dropMultiplier(Location location);

    /** Island members holding the use privilege may use every spawner of their island. */
    boolean sharesSpawners();

    /** Autosell pays only owners who are currently members of the spawner's island. */
    boolean requiresMembershipForAutosell();
}
