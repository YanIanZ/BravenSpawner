package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite: dirty-only batched checkpoints, full flushNow, corrupted_at and the v1→v2 migration. */
class SqlCheckpointTest {
    @TempDir Path folder;
    private Plugin plugin;
    private SqlStorage storage;

    @BeforeEach void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqlCheckpointTest"));
    }

    @AfterEach void tearDown() {
        if (storage != null) storage.close();
    }

    private SqlStorage open() {
        storage = new SqlStorage(plugin, new YamlConfiguration(), SqlStorage.Dialect.SQLITE);
        storage.init();
        return storage;
    }

    private static Spawner spawner(int x) {
        return new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", x, 64, 0,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
    }

    private Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("spawners.db"));
    }

    private long revision(UUID id) throws Exception {
        try (Connection c = raw();
             PreparedStatement ps = c.prepareStatement("SELECT revision FROM braven_spawners WHERE id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row missing for " + id);
                return rs.getLong(1);
            }
        }
    }

    @Test void checkpointWritesOnlyChangedRowsAndFlushNowRewritesAll() throws Exception {
        SqlStorage sql = open();
        Spawner a = spawner(0), b = spawner(1), c = spawner(2);
        List<Spawner> live = List.of(a, b, c);
        live.forEach(sql::upsert);
        sql.flushAll(live);
        assertEquals(0, revision(a.id()));
        assertEquals(0, revision(c.id()));

        b.addXp(5);            // mutated but never reported: caught by its revision
        sql.upsert(a);         // reported dirty
        sql.flushAll(live);
        assertEquals(1, revision(a.id()));
        assertEquals(1, revision(b.id()));
        assertEquals(0, revision(c.id()), "an unchanged row must not be rewritten by a checkpoint");

        sql.flushAll(live, true);
        assertEquals(2, revision(a.id()));
        assertEquals(1, revision(c.id()), "flushNow rewrites every live row");
    }

    @Test void deletesAreCommittedAndNotResurrected() throws Exception {
        SqlStorage sql = open();
        Spawner a = spawner(0);
        sql.upsert(a);
        sql.flushAll(List.of(a));
        sql.delete(a.id());
        sql.flushAll(List.of(a));
        try (Connection c = raw(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM braven_spawners")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test void corruptionTimestampSurvivesReload() {
        SqlStorage sql = open();
        Spawner a = spawner(0);
        a.restoreCorruption(true, 12_345L);
        sql.upsert(a);
        sql.flushAll(List.of(a));
        sql.close();

        Spawner loaded = open().loadAll().get(0);
        assertTrue(loaded.corrupted());
        assertEquals(12_345L, loaded.corruptedAt());
    }

    @Test void versionOneDatabaseGainsCorruptedAtColumn() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE braven_schema_version (id INT PRIMARY KEY, version INT NOT NULL, updated_at BIGINT NOT NULL)");
            st.executeUpdate("INSERT INTO braven_schema_version VALUES (1,1,0)");
            st.executeUpdate("""
                    CREATE TABLE braven_spawners (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36) NOT NULL,
                      world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      kind VARCHAR(8) NOT NULL, product VARCHAR(64) NOT NULL, stack INT NOT NULL DEFAULT 1,
                      tier INT NOT NULL DEFAULT 0, hologram BOOLEAN NOT NULL, corrupted BOOLEAN NOT NULL DEFAULT 0,
                      xp INT NOT NULL DEFAULT 0, produced BIGINT NOT NULL DEFAULT 0, trusted TEXT, items TEXT,
                      revision BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0)""");
            st.executeUpdate("INSERT INTO braven_spawners (id,owner,world,x,y,z,kind,product,hologram,corrupted,items)"
                    + " VALUES ('" + id + "','" + UUID.randomUUID() + "','world',0,64,0,'MOB','ZOMBIE',1,1,'')");
        }
        List<Spawner> loaded = open().loadAll();
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).corrupted());
        assertTrue(loaded.get(0).corruptedAt() > 0, "a legacy corrupted row keeps a load-time timestamp");
        try (Connection c = raw(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM braven_schema_version WHERE id=1")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test void poolSizeAcceptsDocumentedAndLegacyKeys() {
        YamlConfiguration documented = new YamlConfiguration();
        documented.set("mysql.max-pool", 15);
        assertEquals(15, SqlStorage.poolSize(documented));
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("pool-size", 7);
        assertEquals(7, SqlStorage.poolSize(legacy));
        assertEquals(5, SqlStorage.poolSize(new YamlConfiguration()));
    }
}
