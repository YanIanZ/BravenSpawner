package dev.yanianz.bravenspawner.holo;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Live finding (1.4.1): every data change refreshes the hologram, coalesced per spawner. */
class HologramRefreshTest {
    @TempDir Path folder;
    final List<Runnable> later = new ArrayList<>();
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final Spawner spawner = mock(Spawner.class);
    MockedStatic<Schedulers> schedulers;
    Holograms holograms;

    @BeforeEach void setUp() throws Exception {
        Files.writeString(folder.resolve("holograms.yml"), "enabled: true\nhologram:\n  content:\n    - '%stack%'\n");
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getName()).thenReturn("BravenSpawner");
        when(spawner.id()).thenReturn(UUID.randomUUID());
        when(spawner.hologram()).thenReturn(true);
        schedulers = mockStatic(Schedulers.class);
        schedulers.when(() -> Schedulers.later(any(), anyLong(), any())).thenAnswer(call -> {
            later.add(call.getArgument(2));
            return null;
        });
        holograms = spy(new Holograms(plugin));
        doNothing().when(holograms).update(any());
    }

    @AfterEach void tearDown() {
        schedulers.close();
    }

    @Test void aBurstOfChangesCostsOneUpdate() {
        assertTrue(holograms.config().enabled());
        holograms.refreshSoon(spawner);
        holograms.refreshSoon(spawner);
        holograms.refreshSoon(spawner);
        assertEquals(1, later.size(), "coalesced while one refresh is queued");
        later.removeFirst().run();
        verify(holograms, times(1)).update(spawner);
        holograms.refreshSoon(spawner);
        assertEquals(1, later.size(), "a later change queues again");
    }

    @Test void hiddenHologramsAreNotRefreshed() {
        when(spawner.hologram()).thenReturn(false);
        holograms.refreshSoon(spawner);
        assertTrue(later.isEmpty());
    }
}
