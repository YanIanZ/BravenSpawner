package dev.yanianz.bravemspawner.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.yanianz.bravemspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * SQLite / MySQL backend behind one dialect switch.
 *
 * <p>Hikari owns the pool; {@link #flushAll} runs in a single transaction so a
 * half-written table is impossible. SQLite is opened in WAL mode so a save never
 * blocks a read.
 */
public final class SqlStorage implements Storage {

    public enum Dialect { SQLITE, MYSQL }

    private final Plugin plugin;
    private final ConfigurationSection section;
    private final Dialect dialect;
    private HikariDataSource dataSource;

    public SqlStorage(Plugin plugin, ConfigurationSection section, Dialect dialect) {
        this.plugin = plugin;
        this.section = section;
        this.dialect = dialect;
    }

    @Override
    public void init() {
        HikariConfig cfg = new HikariConfig();
        cfg.setMaximumPoolSize(Math.max(2, section.getInt("pool-size", 5)));
        cfg.setPoolName("BravemSpawner");

        if (dialect == Dialect.SQLITE) {
            cfg.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder().toPath().resolve("spawners.db"));
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setConnectionTestQuery("SELECT 1");
            cfg.addDataSourceProperty("journal_mode", "WAL");
            cfg.addDataSourceProperty("synchronous", "NORMAL");
        } else {
            ConfigurationSection mysql = section.getConfigurationSection("mysql");
            String host = mysql != null ? mysql.getString("host", "localhost") : "localhost";
            int port = mysql != null ? mysql.getInt("port", 3306) : 3306;
            String db = mysql != null ? mysql.getString("database", "bravemspawner") : "bravemspawner";
            boolean ssl = mysql != null && mysql.getBoolean("use-ssl", false);
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=" + ssl + "&characterEncoding=utf8");
            cfg.setUsername(mysql != null ? mysql.getString("user", "root") : "root");
            cfg.setPassword(mysql != null ? mysql.getString("password", "") : "");
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }

        dataSource = new HikariDataSource(cfg);
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bravem_spawners (
                      id VARCHAR(36) PRIMARY KEY,
                      owner VARCHAR(36) NOT NULL,
                      world VARCHAR(64) NOT NULL,
                      x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      kind VARCHAR(8) NOT NULL,
                      product VARCHAR(64) NOT NULL,
                      stack INT NOT NULL DEFAULT 1,
                      tier INT NOT NULL DEFAULT 0,
                      hologram BOOLEAN NOT NULL,
                      corrupted BOOLEAN NOT NULL DEFAULT 0,
                      xp INT NOT NULL DEFAULT 0,
                      produced BIGINT NOT NULL DEFAULT 0,
                      trusted TEXT,
                      items %s
                    )""".formatted(dialect == Dialect.MYSQL ? "LONGTEXT" : "TEXT"));
        } catch (Exception e) {
            throw new IllegalStateException("schema creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Spawner> loadAll() {
        List<Spawner> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM bravem_spawners");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Spawner spawner = new Spawner(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("owner")),
                        rs.getString("world"),
                        rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                        Storage.Codec.kindOf(rs.getString("kind")),
                        rs.getString("product"),
                        rs.getInt("stack"),
                        rs.getInt("tier"),
                        rs.getBoolean("hologram"));
                spawner.trusted().addAll(Storage.Codec.decodeTrusted(rs.getString("trusted")));
                spawner.replaceContents(Storage.Codec.decodeItems(rs.getString("items")));
                spawner.addXp(rs.getInt("xp"));
                spawner.produced(rs.getLong("produced"));
                if (rs.getBoolean("corrupted")) spawner.corrupt();
                out.add(spawner);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("load failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void upsert(Spawner spawner) {
        try (Connection c = dataSource.getConnection()) {
            write(c, spawner);
        } catch (Exception e) {
            plugin.getLogger().warning("upsert failed: " + e.getMessage());
        }
    }

    private void write(Connection c, Spawner s) throws Exception {
        String sql = dialect == Dialect.MYSQL
                ? "REPLACE INTO bravem_spawners (id,owner,world,x,y,z,kind,product,stack,tier,hologram,corrupted,xp,produced,trusted,items) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT OR REPLACE INTO bravem_spawners (id,owner,world,x,y,z,kind,product,stack,tier,hologram,corrupted,xp,produced,trusted,items) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.id().toString());
            ps.setString(2, s.owner().toString());
            ps.setString(3, s.world());
            ps.setInt(4, s.x());
            ps.setInt(5, s.y());
            ps.setInt(6, s.z());
            ps.setString(7, s.kind().name());
            ps.setString(8, s.product());
            ps.setInt(9, s.stack());
            ps.setInt(10, s.tier());
            ps.setBoolean(11, s.hologram());
            ps.setBoolean(12, s.corrupted());
            ps.setInt(13, s.storedXp());
            ps.setLong(14, s.produced());
            ps.setString(15, Storage.Codec.encodeTrusted(s.trusted()));
            ps.setString(16, Storage.Codec.encodeItems(s.contents()));
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM bravem_spawners WHERE id=?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("delete failed: " + e.getMessage());
        }
    }

    @Override
    public void flushAll(Collection<Spawner> spawners) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (var st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM bravem_spawners");
                }
                for (Spawner s : spawners) write(c, s);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                plugin.getLogger().severe("flush rolled back: " + e.getMessage());
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("flush failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (dataSource != null) dataSource.close();
    }
}
