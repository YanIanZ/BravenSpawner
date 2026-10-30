package dev.yanianz.bravenspawner.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQLite / MySQL backend behind one dialect switch.
 *
 * <p>Hikari owns the pool. A periodic checkpoint writes only the records that changed
 * since their last committed write: ids queued through {@link #upsert(Spawner)} plus any
 * live record whose {@link Spawner#revision()} moved past the revision last committed
 * for it. Those rows are UPSERTed with {@code addBatch}/{@code executeBatch} inside one
 * transaction; deletes are batched the same way. Only {@link #flushAll(Collection, boolean)}
 * with {@code full=true} (the manual {@code flushNow} path) rewrites every live row.
 * The table is never cleared, so a failure rolls back that transaction only and leaves
 * every previously committed row intact.
 *
 * <p>A {@code braven_schema_version} row records the applied schema version and
 * {@link #applyMigration(Connection, int)} is the hook future column additions plug
 * into, so an upgrade never requires dropping the table.
 *
 * <p>Load failures are thrown and flip {@link #isHealthy()} to false: the caller
 * must not persist an empty map over records that could not be read. Rows whose
 * inventory blob is malformed are skipped (quarantined in place) instead of being
 * rewritten with empty storage.
 */
public final class SqlStorage implements Storage {

    public enum Dialect { SQLITE, MYSQL }

    /** Bump together with a new {@code applyMigration} branch. */
    private static final int SCHEMA_VERSION = 2;

    private static final String TABLE = "braven_spawners";
    private static final String VERSION_TABLE = "braven_schema_version";

    private static final String COLUMNS =
            "id,owner,world,x,y,z,kind,product,stack,tier,hologram,corrupted,corrupted_at,xp,produced,trusted,items,revision,updated_at";
    private static final String PLACEHOLDERS = "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?";

    private static final String SQLITE_UPSERT =
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ")"
                    + " ON CONFLICT(id) DO UPDATE SET"
                    + " owner=excluded.owner, world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,"
                    + " kind=excluded.kind, product=excluded.product, stack=excluded.stack, tier=excluded.tier,"
                    + " hologram=excluded.hologram, corrupted=excluded.corrupted,"
                    + " corrupted_at=excluded.corrupted_at, xp=excluded.xp,"
                    + " produced=excluded.produced, trusted=excluded.trusted, items=excluded.items,"
                    + " revision=" + TABLE + ".revision + 1, updated_at=excluded.updated_at";

    private static final String MYSQL_UPSERT =
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ")"
                    + " ON DUPLICATE KEY UPDATE"
                    + " owner=VALUES(owner), world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),"
                    + " kind=VALUES(kind), product=VALUES(product), stack=VALUES(stack), tier=VALUES(tier),"
                    + " hologram=VALUES(hologram), corrupted=VALUES(corrupted),"
                    + " corrupted_at=VALUES(corrupted_at), xp=VALUES(xp),"
                    + " produced=VALUES(produced), trusted=VALUES(trusted), items=VALUES(items),"
                    + " revision=revision + 1, updated_at=VALUES(updated_at)";

    private final Plugin plugin;
    private final ConfigurationSection section;
    private final Dialect dialect;

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> tombstones = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingDeletes = ConcurrentHashMap.newKeySet();
    /** Revision of each record as of its last committed write (or its load). */
    private final java.util.Map<UUID, Long> committed = new ConcurrentHashMap<>();

    private volatile boolean healthy = true;
    private volatile HikariDataSource dataSource;

    public SqlStorage(Plugin plugin, ConfigurationSection section, Dialect dialect) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.section = java.util.Objects.requireNonNull(section, "section");
        this.dialect = java.util.Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void init() {
        registerDrivers();
        HikariConfig cfg = new HikariConfig();
        cfg.setMaximumPoolSize(poolSize(section));
        cfg.setPoolName("BravenSpawner");

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
            String db = mysql != null ? mysql.getString("database", "bravenspawner") : "bravenspawner";
            boolean ssl = mysql != null && mysql.getBoolean("use-ssl", false);
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=" + ssl + "&characterEncoding=utf8");
            cfg.setUsername(mysql != null ? mysql.getString("user", "root") : "root");
            cfg.setPassword(mysql != null ? mysql.getString("password", "") : "");
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }

        dataSource = new HikariDataSource(cfg);
        try (Connection c = dataSource.getConnection()) {
            migrate(c);
        } catch (Exception e) {
            // The plugin will disable itself; release the pool rather than leaking
            // it because onDisable only closes storage once a manager exists.
            HikariDataSource failed = dataSource;
            dataSource = null;
            if (failed != null) failed.close();
            throw new IllegalStateException("schema creation failed: " + e.getMessage(), e);
        }
        healthy = true;
    }

    /**
     * Pool size from {@code mysql.max-pool} (the documented key), {@code mysql.pool-size}
     * or a top-level {@code pool-size}, in that order; at least 2.
     */
    static int poolSize(ConfigurationSection section) {
        ConfigurationSection mysql = section.getConfigurationSection("mysql");
        int size = 5;
        if (mysql != null && mysql.isInt("max-pool")) size = mysql.getInt("max-pool");
        else if (mysql != null && mysql.isInt("pool-size")) size = mysql.getInt("pool-size");
        else if (section.isInt("pool-size")) size = section.getInt("pool-size");
        return Math.max(2, size);
    }

    /**
     * Drives static references to the JDBC drivers.
     *
     * <p>Both drivers are normally found through {@code DriverManager} using a
     * class name, which a shrinking step cannot see. Referencing the classes here
     * keeps them in the jar and registers them before Hikari touches the pool.
     */
    private void registerDrivers() {
        try {
            java.sql.DriverManager.registerDriver(new org.sqlite.JDBC());
        } catch (Exception ignored) {
            // driver already registered or unavailable; Hikari reports it clearly
        }
        try {
            java.sql.DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        } catch (Exception ignored) {
            // the MySQL driver is only needed when type: MYSQL
        }
    }

    // ---------------------------------------------------------------- schema

    /**
     * Forward-only migration runner. A database that predates the version table
     * reports version 0 and is upgraded in place; a newer database is refused
     * rather than silently written by an older build.
     */
    private void migrate(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE
                    + " (id INT PRIMARY KEY, version INT NOT NULL, updated_at BIGINT NOT NULL)");
        }
        int current = readVersion(c);
        if (current > SCHEMA_VERSION) {
            throw new SQLException("database schema version " + current
                    + " is newer than this build (" + SCHEMA_VERSION + ")");
        }
        for (int next = current + 1; next <= SCHEMA_VERSION; next++) {
            applyMigration(c, next);
        }
        writeVersion(c, SCHEMA_VERSION);
    }

    /**
     * Migration hook: add a branch for every new {@code SCHEMA_VERSION}. Branches
     * must be additive (create table / add column) so an existing install keeps
     * its rows.
     */
    private void applyMigration(Connection c, int version) throws SQLException {
        switch (version) {
            case 1 -> {
                createSpawnersTable(c);
                // 1.0.x databases already have braven_spawners but no bookkeeping
                // columns; add them without touching the existing rows.
                ensureColumn(c, TABLE, "revision", "BIGINT NOT NULL DEFAULT 0");
                ensureColumn(c, TABLE, "updated_at", "BIGINT NOT NULL DEFAULT 0");
            }
            // v2: corruption timestamp, so a reload keeps the original corruption time.
            case 2 -> ensureColumn(c, TABLE, "corrupted_at", "BIGINT NOT NULL DEFAULT 0");
            default -> throw new SQLException("no migration registered for schema version " + version);
        }
    }

    private void createSpawnersTable(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS braven_spawners (
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
                      corrupted_at BIGINT NOT NULL DEFAULT 0,
                      xp INT NOT NULL DEFAULT 0,
                      produced BIGINT NOT NULL DEFAULT 0,
                      trusted TEXT,
                      items %s,
                      revision BIGINT NOT NULL DEFAULT 0,
                      updated_at BIGINT NOT NULL DEFAULT 0
                    )""".formatted(dialect == Dialect.MYSQL ? "LONGTEXT" : "TEXT"));
        }
    }

    private int readVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT version FROM " + VERSION_TABLE + " WHERE id=1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void writeVersion(Connection c, int version) throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "INSERT INTO " + VERSION_TABLE + " (id,version,updated_at) VALUES (1,?,?)"
                        + " ON DUPLICATE KEY UPDATE version=VALUES(version), updated_at=VALUES(updated_at)"
                : "INSERT INTO " + VERSION_TABLE + " (id,version,updated_at) VALUES (1,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET version=excluded.version, updated_at=excluded.updated_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, version);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Adds a column only when the catalog says it is missing. Metadata is used
     * instead of catching a duplicate-column error so a genuine DDL failure is
     * never mistaken for "already applied".
     */
    private void ensureColumn(Connection c, String table, String column, String definition)
            throws SQLException {
        if (hasColumn(c, table, column)) return;
        try (var st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection c, String table, String column) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String candidate : new String[]{
                table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getColumns(c.getCatalog(), null, candidate, null)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ load

    @Override
    public List<Spawner> loadAll() {
        HikariDataSource pool = requireDataSource();
        List<Spawner> out = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM " + TABLE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Spawner spawner = readRow(rs);
                if (spawner != null) out.add(spawner);
            }
        } catch (SQLException e) {
            healthy = false;
            plugin.getLogger().severe("spawner load failed; storage marked unhealthy: " + e.getMessage());
            throw new StorageException("spawner load failed", e);
        }
        healthy = true;
        return out;
    }

    /**
     * Reads one row, or skips it when its inventory cannot be decoded. The row is
     * left untouched in the database, which is the quarantine: no later UPSERT of
     * the live set can replace it, because it is not part of that set.
     */
    private Spawner readRow(ResultSet rs) throws SQLException {
        String rawId = rs.getString("id");
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("quarantined spawner row with malformed id " + rawId);
            return null;
        }
        List<ItemStack> items = Storage.Codec.decodeItems(rs.getString("items"), rawId);
        if (items == null) {
            plugin.getLogger().warning("quarantined spawner row " + rawId + ": inventory is unreadable");
            return null;
        }
        try {
            Spawner spawner = new Spawner(
                    id,
                    UUID.fromString(rs.getString("owner")),
                    rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    Storage.Codec.kindOf(rs.getString("kind")),
                    rs.getString("product"),
                    rs.getInt("stack"),
                    rs.getInt("tier"),
                    rs.getBoolean("hologram"));
            spawner.trusted().addAll(Storage.Codec.decodeTrusted(rs.getString("trusted")));
            spawner.replaceContents(items);
            spawner.addXp(rs.getInt("xp"));
            spawner.produced(rs.getLong("produced"));
            Storage.Codec.restoreCorruption(spawner, rs.getBoolean("corrupted"), rs.getLong("corrupted_at"));
            committed.put(id, spawner.revision());
            return spawner;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("quarantined malformed spawner row " + rawId + ": " + e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------------------------- write

    /** Gameplay paths enqueue only; the checkpoint thread owns database I/O. */
    @Override public void upsert(Spawner spawner) {
        if (spawner == null) return;
        tombstones.remove(spawner.id());
        pendingDeletes.remove(spawner.id());
        dirty.add(spawner.id());
    }

    @Override public void delete(UUID id) {
        if (id == null) return;
        tombstones.add(id);
        pendingDeletes.add(id);
        dirty.add(id);
    }

    /** Checkpoint flush: only changed records. */
    @Override
    public void flushAll(Collection<Spawner> spawners) {
        flushAll(spawners, false);
    }

    @Override
    public void flushAll(Collection<Spawner> spawners, boolean full) {
        if (spawners == null) {
            throw new StorageException("flush called without a spawner collection");
        }
        if (!healthy) {
            // A failed load leaves the live map partial; writing it would erase the
            // rows that were never read. Refuse until a load succeeds.
            throw new StorageException("refusing to flush after a failed load");
        }
        HikariDataSource pool = requireDataSource();

        Set<UUID> pending = new HashSet<>(dirty);
        dirty.removeAll(pending);

        Set<UUID> deletes = new HashSet<>(pendingDeletes);
        Set<UUID> live = new HashSet<>();
        List<Spawner> writes = new ArrayList<>();
        for (Spawner s : spawners) {
            // A checkpoint may hold a snapshot taken before a delete; never let that
            // snapshot resurrect a row removed on purpose.
            if (s == null || tombstones.contains(s.id())) continue;
            live.add(s.id());
            if (full || pending.contains(s.id()) || changedSinceCommit(s)) writes.add(s);
        }
        List<UUID> rowDeletes = new ArrayList<>();
        for (UUID id : deletes) if (!live.contains(id)) rowDeletes.add(id);

        java.util.Map<UUID, Long> revisions = new java.util.HashMap<>();
        try (Connection c = pool.getConnection()) {
            inTransaction(c, () -> {
                deleteRows(c, rowDeletes);
                writeRows(c, writes, revisions);
            });
        } catch (Exception e) {
            dirty.addAll(pending);
            plugin.getLogger().severe("checkpoint flush failed; last committed rows kept: "
                    + e.getMessage());
            throw new StorageException("checkpoint flush failed", e);
        }

        // Committed: only now is it safe to forget the deletes this pass retried.
        pendingDeletes.removeAll(deletes);
        committed.putAll(revisions);
        rowDeletes.forEach(committed::remove);
    }

    private boolean changedSinceCommit(Spawner s) {
        Long last = committed.get(s.id());
        return last == null || last != s.revision();
    }

    /** Rows written by the last flush call; exposed for tests. */
    int committedRevisions() {
        return committed.size();
    }

    /**
     * Runs {@code body} inside one transaction and restores the connection's
     * previous auto-commit flag. A mid-batch error rolls back the whole batch,
     * never the rows committed by earlier calls.
     */
    private void inTransaction(Connection c, SqlWork body) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            body.run();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private int batchSize() {
        return Math.max(1, section.getInt("batch_size", 300));
    }

    /**
     * UPSERTs {@code rows} through one prepared statement and JDBC batches of
     * {@code batch_size}. Each row's revision is read before it is encoded, so a
     * mutation that lands during the encode still differs and is written next time.
     */
    private void writeRows(Connection c, List<Spawner> rows, java.util.Map<UUID, Long> revisions)
            throws SQLException {
        if (rows.isEmpty()) return;
        String sql = dialect == Dialect.MYSQL ? MYSQL_UPSERT : SQLITE_UPSERT;
        int limit = batchSize();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int queued = 0;
            long now = System.currentTimeMillis();
            for (Spawner s : rows) {
                long revision = s.revision();
                bindRow(ps, s, now);
                ps.addBatch();
                revisions.put(s.id(), revision);
                if (++queued >= limit) {
                    ps.executeBatch();
                    queued = 0;
                }
            }
            if (queued > 0) ps.executeBatch();
        }
    }

    private void bindRow(PreparedStatement ps, Spawner s, long now) throws SQLException {
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
        ps.setLong(13, s.corruptedAt());
        ps.setInt(14, s.storedXp());
        ps.setLong(15, s.produced());
        ps.setString(16, Storage.Codec.encodeTrusted(s.trusted()));
        ps.setString(17, Storage.Codec.encodeItems(s.contents()));
        ps.setLong(18, now);
    }

    private void deleteRows(Connection c, List<UUID> ids) throws SQLException {
        if (ids.isEmpty()) return;
        int limit = batchSize();
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id=?")) {
            int queued = 0;
            for (UUID id : ids) {
                ps.setString(1, id.toString());
                ps.addBatch();
                if (++queued >= limit) {
                    ps.executeBatch();
                    queued = 0;
                }
            }
            if (queued > 0) ps.executeBatch();
        }
    }

    // ------------------------------------------------------------- lifecycle

    private HikariDataSource requireDataSource() {
        HikariDataSource pool = dataSource;
        if (pool == null) throw new StorageException("storage is not initialised");
        return pool;
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public boolean isDirty() {
        return !dirty.isEmpty() || !pendingDeletes.isEmpty();
    }

    @Override
    public int pendingWrites() {
        Set<UUID> pending = new HashSet<>(dirty);
        pending.addAll(pendingDeletes);
        return pending.size();
    }

    @Override
    public void close() {
        HikariDataSource pool = dataSource;
        dataSource = null;
        if (pool != null) pool.close();
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
