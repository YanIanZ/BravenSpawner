package dev.yanianz.bravenspawner.restrictions;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;

public final class RestrictedSpawners {
    private final BravenSpawner plugin;
    private volatile RestrictionRules rules = RestrictionRules.from(new YamlConfiguration());
    public RestrictedSpawners(BravenSpawner plugin) { this.plugin = plugin; reload(); }
    public void reload() {
        File file = new File(plugin.getDataFolder(), "other/restricted_spawners.yml");
        if (!file.exists()) plugin.saveResource("other/restricted_spawners.yml", false);
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.load(file);
            rules = RestrictionRules.from(config);
        } catch (Exception error) {
            plugin.getLogger().warning("Keeping previous spawner restrictions: " + error.getMessage());
        }
    }
    public RestrictionRules rules() { return rules; }
    public boolean canOpen(Player player, Spawner spawner) {
        boolean allowed = rules.canOpen(spawner.product(), bypass(player));
        if (!allowed) notify(player, spawner);
        return allowed;
    }
    public boolean canTakeItems(Player player, Spawner spawner) {
        boolean allowed = rules.canTakeItems(spawner.product(), bypass(player));
        if (!allowed) notify(player, spawner);
        return allowed;
    }
    private boolean bypass(Player player) { return player != null && player.hasPermission("bravenspawner.restrictions.bypass"); }
    private void notify(Player player, Spawner spawner) {
        if (player != null) player.sendMessage(Text.of(rules.message().replace("%spawner_type%", spawner.product())));
    }
}
