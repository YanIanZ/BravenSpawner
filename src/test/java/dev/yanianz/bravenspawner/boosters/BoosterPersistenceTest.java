package dev.yanianz.bravenspawner.boosters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BoosterPersistenceTest {
    @TempDir Path directory;
    final UUID player = UUID.randomUUID(), spawner = UUID.randomUUID();

    @Test void restartPreservesAbsoluteDeadlinesScopesAndPermanentAutosell() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        BoosterState original = new BoosterState(clock::get);
        original.grantPlayer(Boosters.Type.XP, player, 2, 10);
        original.grantSpawner(Boosters.Type.SPEED, spawner, 3, 2);
        original.grantSpawner(Boosters.Type.AUTOSELL, spawner, 0, -1);
        original.replacePersonal(player, Map.of(Boosters.Type.CASH, 5.0));
        Path file = directory.resolve("active.properties");
        BoosterStore store = new BoosterStore(file);
        assertTrue(store.load().isEmpty());
        store.save(original.snapshot());
        clock.set(4_000);
        BoosterState restarted = new BoosterState(clock::get);
        restarted.restore(new BoosterStore(file).load());
        assertEquals(7, restarted.remainingSeconds(player));
        assertEquals(2, restarted.multiplier(Boosters.Type.XP, player, spawner));
        assertEquals(1, restarted.multiplier(Boosters.Type.SPEED, player, spawner));
        assertTrue(restarted.active(Boosters.Type.AUTOSELL, player, spawner));
        assertEquals(1, restarted.personalMultiplier(Boosters.Type.CASH, player));
        clock.set(11_000);
        assertEquals(1, restarted.multiplier(Boosters.Type.XP, player, spawner));
        assertTrue(restarted.active(Boosters.Type.AUTOSELL, player, spawner));
    }

    @Test void corruptLedgerIsPreservedAndCannotBeOverwritten() throws Exception {
        Path file = directory.resolve("active.properties");
        String corrupt = "version=1\ncount=1\n0.type=XP\n0.subject=bad-uuid\n";
        Files.writeString(file, corrupt);
        BoosterStore store = new BoosterStore(file);
        assertThrows(java.io.IOException.class, store::load);
        assertThrows(java.io.IOException.class, () -> store.save(List.of()));
        assertEquals(corrupt, Files.readString(file));
    }

    @Test void futureVersionsAndInvalidValuesAreRejected() throws Exception {
        Path file = directory.resolve("active.properties");
        for (String content : List.of("version=2\ncount=0\n", "version=1\ncount=-1\n")) {
            Files.writeString(file, content);
            assertThrows(java.io.IOException.class, () -> new BoosterStore(file).load());
        }
        assertThrows(IllegalArgumentException.class, () -> new BoosterState.SavedEffect(
                Boosters.Type.XP, player, true, Double.NaN, 10_000));
    }

    @Test void savingEmptySnapshotRemovesPreviouslySavedEffects() throws Exception {
        Path file = directory.resolve("active.properties");
        BoosterStore store = new BoosterStore(file);
        store.load();
        store.save(List.of(new BoosterState.SavedEffect(Boosters.Type.LOOT, spawner, false, 2, Long.MAX_VALUE)));
        store.save(List.of());
        assertTrue(new BoosterStore(file).load().isEmpty());
    }
}
