package dev.yanianz.bravenspawner.boosters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Versioned local ledger, independent of the spawner database schema. */
final class BoosterStore {
    private final Path file;
    private boolean writable;

    BoosterStore(Path file) { this.file = file.toAbsolutePath(); }

    synchronized List<BoosterState.SavedEffect> load() throws IOException {
        writable = false;
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data.load(reader);
        } catch (java.nio.file.NoSuchFileException exception) {
            writable = true;
            return List.of();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed booster ledger", exception);
        }
        try {
            if (!"1".equals(data.getProperty("version"))) throw new IllegalArgumentException("unsupported version");
            int count = Integer.parseInt(required(data, "count"));
            if (count < 0 || count > 1_000_000) throw new IllegalArgumentException("invalid count");
            List<BoosterState.SavedEffect> effects = new ArrayList<>();
            var keys = new HashSet<String>();
            for (int i = 0; i < count; i++) {
                String prefix = i + ".";
                String scope = required(data, prefix + "scope");
                if (!scope.equals("player") && !scope.equals("spawner")) throw new IllegalArgumentException("invalid scope");
                var effect = new BoosterState.SavedEffect(
                        Boosters.Type.valueOf(required(data, prefix + "type")),
                        UUID.fromString(required(data, prefix + "subject")), scope.equals("player"),
                        Double.parseDouble(required(data, prefix + "multiplier")),
                        Long.parseLong(required(data, prefix + "expiresAt")));
                if (!keys.add(effect.type() + ":" + effect.subject() + ":" + scope)) {
                    throw new IllegalArgumentException("duplicate effect");
                }
                effects.add(effect);
            }
            writable = true;
            return List.copyOf(effects);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid booster ledger: " + exception.getMessage(), exception);
        }
    }

    private static String required(Properties data, String key) {
        String value = data.getProperty(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    synchronized void save(List<BoosterState.SavedEffect> effects) throws IOException {
        if (!writable) throw new IOException("Booster ledger was not loaded successfully; refusing overwrite");
        Properties data = new Properties();
        data.setProperty("version", "1");
        data.setProperty("count", Integer.toString(effects.size()));
        for (int i = 0; i < effects.size(); i++) {
            var effect = effects.get(i);
            String prefix = i + ".";
            data.setProperty(prefix + "type", effect.type().name());
            data.setProperty(prefix + "subject", effect.subject().toString());
            data.setProperty(prefix + "scope", effect.player() ? "player" : "spawner");
            data.setProperty(prefix + "multiplier", Double.toString(effect.multiplier()));
            data.setProperty(prefix + "expiresAt", Long.toString(effect.expiresAt()));
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "active-boosters-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                data.store(writer, "BravenSpawner active timed boosters; epoch milliseconds");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
