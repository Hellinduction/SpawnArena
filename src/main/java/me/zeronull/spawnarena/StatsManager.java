package me.zeronull.spawnarena;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.zeronull.spawnarena.config.impl.DatabaseConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StatsManager {

    private final HikariDataSource dataSource;
    private final boolean mysql;
    private final File h2File;
    /**
     * This server's ID (server-id.txt), stored with every row so several servers can share one database.
     * Always a validated UUID, so it's safe to put straight into SQL as {@link #sid}.
     */
    private final String serverId;
    private final String sid;

    public StatsManager(SpawnArena plugin, DatabaseConfig config) {
        plugin.getDataFolder().mkdirs();
        this.serverId = loadServerId(plugin.getDataFolder());
        this.sid = "'" + this.serverId + "'";
        this.mysql = config.type.equalsIgnoreCase("MYSQL");
        this.h2File = new File(plugin.getDataFolder(), "stats");

        final HikariConfig hc = new HikariConfig();
        hc.setMinimumIdle(config.minimumIdle);
        hc.setMaximumPoolSize(config.maximumPoolSize);
        hc.setConnectionTimeout(10_000);
        hc.setIdleTimeout(600_000);
        hc.setMaxLifetime(1_800_000);

        if (this.mysql) {
            hc.setDriverClassName("me.zeronull.spawnarena.lib.mysql.cj.jdbc.Driver");
            hc.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=utf8",
                    config.host, config.port, config.name));
            hc.setUsername(config.username);
            hc.setPassword(config.password);
        } else {
            // H2 embedded — db file lives in the plugin data folder
            plugin.getDataFolder().mkdirs();
            hc.setDriverClassName("me.zeronull.spawnarena.lib.h2.Driver");
            hc.setJdbcUrl(h2Url());
            hc.setUsername("sa");
            hc.setPassword("");
            // An embedded file database gains nothing from recycling connections (that's for MySQL, which drops
            // idle ones), and every recycle risked H2 closing and reopening the database mid-run
            hc.setMaxLifetime(0);
        }

        this.dataSource = new HikariDataSource(hc);
        try {
            createTable();
        } catch (final RuntimeException e) {
            this.dataSource.close(); // the plugin disables itself; don't leave the pool running
            throw e;
        }

        plugin.getDataFolder().mkdirs();
        loadIpSecret(plugin.getDataFolder());
    }

    /**
     * Reads (or creates) server-id.txt. Copying this file to another server makes the two share stats;
     * leaving it out gives the other server its own.
     */
    private static String loadServerId(final File dataFolder) {
        final java.nio.file.Path file = new File(dataFolder, "server-id.txt").toPath();
        try {
            if (java.nio.file.Files.exists(file)) {
                final String id = java.nio.file.Files.readString(file).trim();
                return UUID.fromString(id).toString(); // throws if someone broke the file
            }
            final String id = UUID.randomUUID().toString();
            java.nio.file.Files.writeString(file, id);
            Bukkit.getLogger().info("[SpawnArena] Created server-id.txt (" + id + "). Servers sharing a database keep separate stats by this ID.");
            return id;
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("server-id.txt doesn't contain a valid UUID. Fix it (or restore it from a backup) so this server keeps its stats.", e);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("Couldn't read or create server-id.txt", e);
        }
    }

    public String getServerId() {
        return this.serverId;
    }

    /**
     * Older databases have no server_id: add it, give every existing row to this server (the only one that
     * could have been using it), and make it part of the primary key so servers never overwrite each other.
     */
    private void scopeToServer(final Connection c, final Statement s, final String table, final String... keyColumns) throws SQLException {
        if (!hasColumn(c, table, "server_id")) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN server_id VARCHAR(36) NOT NULL DEFAULT ''");
            final int rows = s.executeUpdate("UPDATE " + table + " SET server_id=" + sid + " WHERE server_id=''");
            Bukkit.getLogger().info("[SpawnArena] Assigned " + rows + " existing " + table + " row(s) to this server (" + serverId + ").");
        }
        if (keyColumns.length == 0 || primaryKey(c, table).contains("server_id"))
            return;
        if (!primaryKey(c, table).isEmpty())
            s.execute("ALTER TABLE " + table + " DROP PRIMARY KEY");
        s.execute("ALTER TABLE " + table + " ADD PRIMARY KEY (server_id, " + String.join(", ", keyColumns) + ")");
    }

    private static java.util.Set<String> primaryKey(final Connection c, final String table) throws SQLException {
        final java.util.Set<String> columns = new java.util.HashSet<>();
        for (final String t : new String[]{table, table.toUpperCase()}) {
            try (ResultSet rs = c.getMetaData().getPrimaryKeys(c.getCatalog(), null, t)) {
                while (rs.next())
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return columns;
    }

    private void createTable() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS arena_stats (" +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "wins INT NOT NULL DEFAULT 0, " +
                    "win_streak INT NOT NULL DEFAULT 0, " +
                    "best_win_streak INT NOT NULL DEFAULT 0, " +
                    "streak_at BIGINT NOT NULL DEFAULT 0, " +
                    "wins_at BIGINT NOT NULL DEFAULT 0, " +
                    "best_at BIGINT NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (server_id, uuid, arena))");
            // Tables created before the "reached at" columns existed
            for (final String column : new String[]{"streak_at", "wins_at", "best_at"})
                if (!hasColumn(c, "arena_stats", column))
                    s.execute("ALTER TABLE arena_stats ADD COLUMN " + column + " BIGINT NOT NULL DEFAULT 0");
            // The streak they had when they last lost one, and when
            if (!hasColumn(c, "arena_stats", "lost_streak"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN lost_streak INT NOT NULL DEFAULT 0");
            if (!hasColumn(c, "arena_stats", "lost_at"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN lost_at BIGINT NOT NULL DEFAULT 0");
            s.execute("CREATE TABLE IF NOT EXISTS arena_meta (" +
                    "server_id VARCHAR(36) NOT NULL, " +
                    "meta_key VARCHAR(64) NOT NULL, " +
                    "meta_value VARCHAR(255) NOT NULL, " +
                    "PRIMARY KEY (server_id, meta_key))");
            if (!hasColumn(c, "arena_stats", "losses"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN losses INT NOT NULL DEFAULT 0");
            // Who they last beat and how many times in a row (for sameOpponentStreakLimit)
            if (!hasColumn(c, "arena_stats", "streak_opponent"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN streak_opponent VARCHAR(36) NULL");
            if (!hasColumn(c, "arena_stats", "streak_opponent_wins"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN streak_opponent_wins INT NOT NULL DEFAULT 0");
            // Free-for-all arenas: counted apart from everything above
            for (final String column : FFA_COLUMNS)
                if (!hasColumn(c, "arena_stats", column))
                    s.execute("ALTER TABLE arena_stats ADD COLUMN " + column + (column.endsWith("_at") ? " BIGINT" : " INT") + " NOT NULL DEFAULT 0");
            // Elo rating (display only): everyone starts at START_ELO
            if (!hasColumn(c, "arena_stats", "elo"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN elo INT NOT NULL DEFAULT " + START_ELO);
            if (!hasColumn(c, "arena_stats", "elo_at"))
                s.execute("ALTER TABLE arena_stats ADD COLUMN elo_at BIGINT NOT NULL DEFAULT 0");
            s.execute("CREATE TABLE IF NOT EXISTS arena_rewards (" +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "last_reward BIGINT NOT NULL, " +
                    "PRIMARY KEY (server_id, uuid, arena))");
            // Reward tables created before same-opponent payout tracking existed
            if (!hasColumn(c, "arena_rewards", "last_opponent"))
                s.execute("ALTER TABLE arena_rewards ADD COLUMN last_opponent VARCHAR(36) NULL");
            if (!hasColumn(c, "arena_rewards", "opponent_payouts"))
                s.execute("ALTER TABLE arena_rewards ADD COLUMN opponent_payouts INT NOT NULL DEFAULT 0");
            if (!hasColumn(c, "arena_rewards", "last_result"))
                s.execute("ALTER TABLE arena_rewards ADD COLUMN last_result VARCHAR(128) NULL");
            s.execute("CREATE TABLE IF NOT EXISTS recent_teammates (" +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "mate VARCHAR(36) NOT NULL, " +
                    "teamed_at BIGINT NOT NULL, " +
                    "PRIMARY KEY (server_id, uuid, mate))");
            s.execute("CREATE TABLE IF NOT EXISTS arena_payouts (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "amount DOUBLE NOT NULL, " +
                    "opponent VARCHAR(36) NULL, " +
                    "opponent2 VARCHAR(36) NULL, " +
                    "leader BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "paid_at BIGINT NOT NULL)");
            // Payout logs created before 2v2 existed
            if (!hasColumn(c, "arena_payouts", "opponent2"))
                s.execute("ALTER TABLE arena_payouts ADD COLUMN opponent2 VARCHAR(36) NULL");
            s.execute("CREATE TABLE IF NOT EXISTS arena_bet_bonuses (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "amount DOUBLE NOT NULL, " +
                    "paid_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS arena_reward_daily (" +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "reward_day VARCHAR(10) NOT NULL, " +
                    "earned DOUBLE NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (server_id, uuid, reward_day))");
            s.execute("CREATE TABLE IF NOT EXISTS bet_escrow (" +
                    "server_id VARCHAR(36) NOT NULL, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "amount DOUBLE NOT NULL, " +
                    "placed_at BIGINT NOT NULL, " +
                    "PRIMARY KEY (server_id, uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS owed_items (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "item BLOB NOT NULL, " +
                    "owed_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS stat_audit (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "server_id VARCHAR(36) NOT NULL DEFAULT '', " +
                    "actor VARCHAR(36) NULL, " +
                    "actor_name VARCHAR(32) NOT NULL, " +
                    "target VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NULL, " +
                    "field VARCHAR(32) NULL, " +
                    "old_value INT NULL, " +
                    "new_value INT NULL, " +
                    "action VARCHAR(32) NOT NULL, " +
                    "changed_at BIGINT NOT NULL, " +
                    "rolled_back BOOLEAN NOT NULL DEFAULT FALSE)");
            s.execute("CREATE TABLE IF NOT EXISTS player_ips (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "ip_hash VARCHAR(64) NOT NULL, " +
                    "last_seen BIGINT NOT NULL, " +
                    "PRIMARY KEY (uuid, ip_hash))");

            // Everything but IP history (shared, so alts are caught across the whole network) is per server
            scopeToServer(c, s, "arena_stats", "uuid", "arena");
            scopeToServer(c, s, "arena_rewards", "uuid", "arena");
            scopeToServer(c, s, "recent_teammates", "uuid", "mate");
            scopeToServer(c, s, "arena_reward_daily", "uuid", "reward_day");
            scopeToServer(c, s, "arena_payouts");
            scopeToServer(c, s, "owed_items");
            scopeToServer(c, s, "stat_audit");

            // Losses weren't stored before: if this server already had stats then, remember when counting began
            if (getMeta(c, "losses_checked") == null) {
                try (ResultSet rs = s.executeQuery("SELECT 1 FROM arena_stats WHERE server_id=" + sid + " LIMIT 1")) {
                    if (rs.next())
                        setMeta(c, "losses_since", String.valueOf(System.currentTimeMillis()));
                }
                setMeta(c, "losses_checked", "1");
            }

            // Get the tables onto disk now rather than whenever H2 next writes
            if (!this.mysql)
                s.execute("CHECKPOINT SYNC");
        } catch (SQLException e) {
            // Running on a half-upgraded database could mix servers' rows, so stop instead (it's retried next start)
            throw new IllegalStateException("Failed to create or upgrade the database tables", e);
        }
    }

    // ── Deleting an arena ───────────────────────────────────────────────────

    /** Removes an arena's stats, reward cooldowns and payout history on this server. Returns rows removed. */
    public int deleteArenaData(final String arena) {
        int removed = 0;
        try (Connection c = dataSource.getConnection()) {
            for (final String table : new String[]{"arena_stats", "arena_rewards", "arena_payouts", "arena_bet_bonuses"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE server_id=" + sid + " AND arena=?")) {
                    ps.setString(1, arena.toLowerCase());
                    removed += ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] deleteArenaData: " + e.getMessage());
        }
        rewardCache.clear();
        lastResults.clear();
        invalidateLeaderboards();
        return removed;
    }

    // ── Bets held during a fight ────────────────────────────────────────────

    /** Records a bet taken from a player for the fight that's starting, so a crash can refund it. */
    public void holdBet(final UUID uuid, final double amount) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO bet_escrow (server_id, uuid, amount, placed_at) VALUES ("
                     + sid + ", ?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), placed_at = VALUES(placed_at)")) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, amount);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[SpawnArena] Couldn't record a bet held for " + uuid + " (" + amount + "): " + e.getMessage());
        }
    }

    /** The bet was settled (paid out, refunded or lost). */
    public void releaseBet(final UUID uuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM bet_escrow WHERE server_id=" + sid + " AND uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] releaseBet: " + e.getMessage());
        }
    }

    /** Bets still held from fights that never finished (the server stopped mid-fight); clears them. */
    public java.util.Map<UUID, Double> takeHeldBets() {
        final java.util.Map<UUID, Double> held = new java.util.HashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT uuid, amount FROM bet_escrow WHERE server_id=" + sid)) {
                while (rs.next())
                    held.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
            }
            st.executeUpdate("DELETE FROM bet_escrow WHERE server_id=" + sid);
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] takeHeldBets: " + e.getMessage());
        }
        return held;
    }

    // ── Items owed to players who logged out mid-fight ──────────────────────

    /** Saves an item to hand back to this player the next time they join (survives restarts). */
    public void addOwedItem(final UUID uuid, final byte[] item) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO owed_items (server_id, uuid, item, owed_at) VALUES (" + sid + ", ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, item);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[SpawnArena] Couldn't save an item owed to " + uuid + ": " + e.getMessage());
        }
    }

    /** Takes (reads and deletes, in one transaction) everything owed to this player. */
    public List<byte[]> takeOwedItems(final UUID uuid) {
        final List<byte[]> items = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement read = c.prepareStatement("SELECT item FROM owed_items WHERE server_id=" + sid + " AND uuid=? ORDER BY id");
                 PreparedStatement delete = c.prepareStatement("DELETE FROM owed_items WHERE server_id=" + sid + " AND uuid=?")) {
                read.setString(1, uuid.toString());
                try (ResultSet rs = read.executeQuery()) {
                    while (rs.next())
                        items.add(rs.getBytes(1));
                }
                if (!items.isEmpty()) {
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] takeOwedItems: " + e.getMessage());
            return List.of();
        }
        return items;
    }

    // ── Staff stat changes (audit log) ──────────────────────────────────────

    /** {@code actor} is null for the console. {@code field}/values are null for changes that aren't a number (cooldown resets). */
    public record AuditEntry(long id, UUID actor, String actorName, UUID target, String arena, String field,
                             Integer oldValue, Integer newValue, String action, long changedAt, boolean rolledBack) {}

    /** Records a stat change made by staff or the console, and writes it to the server log. */
    public void logStatChange(final UUID actor, final String actorName, final UUID target, final String targetName,
                              final String arena, final String field, final Integer oldValue, final Integer newValue,
                              final String action) {
        final String sql = "INSERT INTO stat_audit (server_id, actor, actor_name, target, arena, field, old_value, new_value, action, changed_at) "
                + "VALUES (" + sid + ", ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, actor == null ? null : actor.toString());
            ps.setString(2, actorName);
            ps.setString(3, target.toString());
            ps.setString(4, arena == null ? null : arena.toLowerCase());
            ps.setString(5, field);
            if (oldValue == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, oldValue);
            if (newValue == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setInt(7, newValue);
            ps.setString(8, action);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] logStatChange: " + e.getMessage());
        }
        Bukkit.getLogger().info(String.format("[SpawnArena] [Stat change] %s %s %s%s%s%s", actorName, action, targetName,
                arena == null ? "" : " in " + arena, field == null ? "" : " " + field,
                oldValue == null ? "" : ": " + oldValue + " -> " + newValue));
    }

    /**
     * Newest first. {@code who} (optional) matches changes made by OR to that player; {@code console}
     * matches only changes made from the console.
     */
    public List<AuditEntry> getAudit(final UUID who, final boolean console, final int page, final int pageSize) {
        final String where = " WHERE server_id=" + sid + (console ? " AND actor IS NULL" : who == null ? "" : " AND (actor=? OR target=?)");
        final String sql = "SELECT * FROM stat_audit" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        final List<AuditEntry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (!console && who != null) {
                ps.setString(i++, who.toString());
                ps.setString(i++, who.toString());
            }
            ps.setInt(i++, pageSize);
            ps.setInt(i, Math.max(0, page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(readAudit(rs));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getAudit: " + e.getMessage());
        }
        return out;
    }

    private static AuditEntry readAudit(final ResultSet rs) throws SQLException {
        final String actor = rs.getString("actor");
        final int oldValue = rs.getInt("old_value");
        final boolean oldNull = rs.wasNull();
        final int newValue = rs.getInt("new_value");
        final boolean newNull = rs.wasNull();
        return new AuditEntry(rs.getLong("id"), actor == null ? null : UUID.fromString(actor), rs.getString("actor_name"),
                UUID.fromString(rs.getString("target")), rs.getString("arena"), rs.getString("field"),
                oldNull ? null : oldValue, newNull ? null : newValue, rs.getString("action"),
                rs.getLong("changed_at"), rs.getBoolean("rolled_back"));
    }

    /** Stat changes by this actor (null = console) since a time that haven't been rolled back yet, newest first. */
    public List<AuditEntry> getRollbackable(final UUID actor, final long since) {
        final String sql = "SELECT * FROM stat_audit WHERE server_id=" + sid + " AND " + (actor == null ? "actor IS NULL" : "actor=?")
                + " AND rolled_back=FALSE AND action <> 'rollback' AND changed_at >= ? ORDER BY id DESC";
        final List<AuditEntry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (actor != null)
                ps.setString(i++, actor.toString());
            ps.setLong(i, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(readAudit(rs));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getRollbackable: " + e.getMessage());
        }
        return out;
    }

    /**
     * Undoes stat changes, newest first. Each is undone by its difference rather than by writing the old
     * value back, so wins earned in fights since then are kept (never below 0). Cooldown resets can't be
     * undone and are only counted. Returns {undone, notUndoable}.
     */
    public int[] rollback(final List<AuditEntry> entries, final UUID performer, final String performerName) {
        int undone = 0, skipped = 0;
        for (final AuditEntry e : entries) {
            if (e.field() == null || e.oldValue() == null || e.newValue() == null || e.arena() == null) {
                skipped++;
                continue;
            }
            final int current = getField(e.target(), e.arena(), e.field());
            final int restored = Math.max(0, current - (e.newValue() - e.oldValue()));
            setField(e.target(), e.arena(), e.field(), restored);
            markRolledBack(e.id());
            final String targetName = Bukkit.getOfflinePlayer(e.target()).getName();
            logStatChange(performer, performerName, e.target(), targetName == null ? e.target().toString() : targetName,
                    e.arena(), e.field(), current, restored, "rollback");
            undone++;
        }
        return new int[]{undone, skipped};
    }

    private void markRolledBack(final long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE stat_audit SET rolled_back=TRUE WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] markRolledBack: " + e.getMessage());
        }
    }

    // ── 2v2 teammate history ────────────────────────────────────────────────

    private final java.util.Map<UUID, List<UUID>> recentTeammatesCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void recordTeammate(UUID uuid, UUID mate) {
        final String sql = "INSERT INTO recent_teammates (server_id, uuid, mate, teamed_at) VALUES (" + sid + ", ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE teamed_at = VALUES(teamed_at)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mate.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordTeammate: " + e.getMessage());
        }
        recentTeammatesCache.remove(uuid);
    }

    /** This player's most recent 2v2 teammates, newest first. */
    public List<UUID> getRecentTeammates(UUID uuid, int limit) {
        final List<UUID> cached = recentTeammatesCache.get(uuid);
        if (cached != null)
            return cached;

        final List<UUID> mates = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mate FROM recent_teammates WHERE server_id=" + sid + " AND uuid=? ORDER BY teamed_at DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    mates.add(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getRecentTeammates: " + e.getMessage());
            return mates;
        }
        recentTeammatesCache.put(uuid, mates);
        return mates;
    }

    // ── Payout log ──────────────────────────────────────────────────────────

    /** {@code opponent2} is only set for 2v2 payouts. */
    public record Payout(String arena, double amount, UUID opponent, UUID opponent2, boolean leader, long paidAt) {}
    public record OpponentTotal(UUID opponent, int payouts, double amount) {}

    /** {@code opponents}: the player(s) beaten — one for 1v1, two for 2v2. */
    public void logPayout(UUID uuid, String arena, double amount, List<UUID> opponents, boolean leader) {
        final String sql = "INSERT INTO arena_payouts (server_id, uuid, arena, amount, opponent, opponent2, leader, paid_at) VALUES (" + sid + ", ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.setDouble(3, amount);
            ps.setString(4, opponents.size() > 0 ? opponents.get(0).toString() : null);
            ps.setString(5, opponents.size() > 1 ? opponents.get(1).toString() : null);
            ps.setBoolean(6, leader);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] logPayout: " + e.getMessage());
        }
    }

    /** Newest first; page 1 is the latest {@code pageSize} payouts. */
    public List<Payout> getPayouts(UUID uuid, int page, int pageSize) {
        final List<Payout> payouts = new ArrayList<>();
        final String sql = "SELECT arena, amount, opponent, opponent2, leader, paid_at FROM arena_payouts WHERE server_id=" + sid + " AND uuid=? " +
                "ORDER BY paid_at DESC, id DESC LIMIT ? OFFSET ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, pageSize);
            ps.setInt(3, Math.max(0, page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String opponent = rs.getString("opponent");
                    final String opponent2 = rs.getString("opponent2");
                    payouts.add(new Payout(rs.getString("arena"), rs.getDouble("amount"),
                            opponent == null ? null : UUID.fromString(opponent),
                            opponent2 == null ? null : UUID.fromString(opponent2),
                            rs.getBoolean("leader"), rs.getLong("paid_at")));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getPayouts: " + e.getMessage());
        }
        return payouts;
    }

    /** Who this player has been paid against most, all time. */
    public List<OpponentTotal> getTopPayoutOpponents(UUID uuid, int limit) {
        final List<OpponentTotal> totals = new ArrayList<>();
        // Both 2v2 opponents count
        final String sql = "SELECT o AS opponent, COUNT(*) AS n, SUM(amount) AS total FROM (" +
                "SELECT opponent AS o, amount FROM arena_payouts WHERE server_id=" + sid + " AND uuid=? " +
                "UNION ALL SELECT opponent2 AS o, amount FROM arena_payouts WHERE server_id=" + sid + " AND uuid=?" +
                ") t WHERE o IS NOT NULL GROUP BY o ORDER BY n DESC, total DESC LIMIT ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    totals.add(new OpponentTotal(UUID.fromString(rs.getString("opponent")), rs.getInt("n"), rs.getDouble("total")));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getTopPayoutOpponents: " + e.getMessage());
        }
        return totals;
    }

    /** A bet bonus paid out (what winning a bet paid on top of getting it back). */
    public void logBetBonus(final UUID uuid, final String arena, final double amount) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO arena_bet_bonuses (server_id, uuid, arena, amount, paid_at) VALUES (" + sid + ", ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.setDouble(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] logBetBonus: " + e.getMessage());
        }
    }

    /** All the money the arenas have paid this player (win rewards + bet bonuses; lost bets aren't taken off): {in this arena, in all}. */
    public double[] getMoneyMade(final UUID uuid, final String arena) {
        final String sql = "SELECT COALESCE(SUM(CASE WHEN arena=? THEN amount ELSE 0 END), 0), COALESCE(SUM(amount), 0) FROM ("
                + "SELECT arena, amount FROM arena_payouts WHERE server_id=" + sid + " AND uuid=? "
                + "UNION ALL SELECT arena, amount FROM arena_bet_bonuses WHERE server_id=" + sid + " AND uuid=?) m";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arena.toLowerCase());
            ps.setString(2, uuid.toString());
            ps.setString(3, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return new double[]{rs.getDouble(1), rs.getDouble(2)};
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getMoneyMade: " + e.getMessage());
        }
        return new double[]{0, 0};
    }

    /** Different players who've had a counted fight in each arena (arena -> count), and in any arena (key ""). */
    public java.util.Map<String, Integer> getPlayerCounts() {
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT arena, COUNT(DISTINCT uuid) FROM arena_stats WHERE server_id=" + sid + " GROUP BY arena ORDER BY arena")) {
                while (rs.next())
                    counts.put(rs.getString(1), rs.getInt(2));
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(DISTINCT uuid) FROM arena_stats WHERE server_id=" + sid)) {
                counts.put("", rs.next() ? rs.getInt(1) : 0);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getPlayerCounts: " + e.getMessage());
        }
        return counts;
    }

    /** {count, total amount} of all payouts this player has received. */
    public double[] getPayoutTotals(UUID uuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM arena_payouts WHERE server_id=" + sid + " AND uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return new double[]{rs.getLong(1), rs.getDouble(2)};
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getPayoutTotals: " + e.getMessage());
        }
        return new double[]{0, 0};
    }

    // ── Daily reward earnings ───────────────────────────────────────────────

    private record CachedDaily(String day, double earned) {}
    private final java.util.Map<UUID, CachedDaily> dailyCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Today's date in server time, e.g. "2026-10-12". The daily cap resets when this changes. */
    /** Minutes until the daily reward cap starts over (midnight, server time), rounded up. */
    public static int minutesUntilDailyReset() {
        final java.time.LocalDateTime now = java.time.LocalDateTime.now();
        final java.time.LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return (int) Math.max(1, java.time.Duration.between(now, midnight).toMinutes() + 1);
    }

    public static String today() {
        return java.time.LocalDate.now().toString();
    }

    /** How much this player has earned from arena win rewards today (all arenas). */
    public double getEarnedToday(UUID uuid) {
        final String day = today();
        final CachedDaily cached = dailyCache.get(uuid);
        if (cached != null && cached.day().equals(day))
            return cached.earned();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT earned FROM arena_reward_daily WHERE server_id=" + sid + " AND uuid=? AND reward_day=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                final double earned = rs.next() ? rs.getDouble(1) : 0;
                dailyCache.put(uuid, new CachedDaily(day, earned));
                return earned;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getEarnedToday: " + e.getMessage());
            return Double.MAX_VALUE; // fail closed: treat as capped
        }
    }

    /** The configured daily cap, or 0 for no limit. */
    public double dailyCap() {
        return SpawnArena.settings == null ? 0 : Math.max(0, SpawnArena.settings.dailyRewardCap);
    }

    public boolean hasReachedDailyCap(UUID uuid) {
        final double cap = dailyCap();
        return cap > 0 && getEarnedToday(uuid) >= cap;
    }

    /** Whether their last opponent won't pay out again (any win against someone new clears it). */
    public boolean isSameOpponentBlocked(UUID uuid, String arena) {
        final int limit = SpawnArena.settings == null ? 0 : SpawnArena.settings.sameOpponentLimit;
        return limit > 0 && getRewardState(uuid, arena).opponentPayouts() >= limit;
    }

    public void addEarnedToday(UUID uuid, double amount) {
        final String day = today();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO arena_reward_daily (server_id, uuid, reward_day, earned) VALUES (" + sid + ", ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE earned = earned + VALUES(earned)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, day);
            ps.setDouble(3, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] addEarnedToday: " + e.getMessage());
        }
        dailyCache.remove(uuid);
    }

    // ── IP history (alt detection) ──────────────────────────────────────────

    private byte[] ipSecret;

    /** Remembers that this player connected from this address. Local/LAN addresses are ignored. */
    public void recordIp(UUID uuid, java.net.InetAddress address) {
        final String hash = hashIp(address);
        if (hash == null)
            return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO player_ips (uuid, ip_hash, last_seen) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, hash);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordIp: " + e.getMessage());
        }
    }

    /** Whether two players have ever connected from the same (non-local) IP address. */
    public boolean sharesIp(UUID a, UUID b) {
        final String sql = "SELECT 1 FROM player_ips p1 JOIN player_ips p2 ON p1.ip_hash = p2.ip_hash " +
                "WHERE p1.uuid=? AND p2.uuid=? LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] sharesIp: " + e.getMessage());
            return true; // fail closed: don't pay if we can't check
        }
    }

    /**
     * Keyed hash of an IP so plain addresses are never stored. The key lives in ip-secret.key in the
     * plugin folder. Returns null for loopback/LAN addresses, which say nothing about who's an alt.
     */
    private String hashIp(final java.net.InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress())
            return null;
        try {
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(ipSecret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(address.getAddress()));
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SpawnArena] hashIp: " + e.getMessage());
            return null;
        }
    }

    private void loadIpSecret(final File dataFolder) {
        final java.nio.file.Path file = new File(dataFolder, "ip-secret.key").toPath();
        try {
            if (java.nio.file.Files.exists(file)) {
                this.ipSecret = java.util.HexFormat.of().parseHex(java.nio.file.Files.readString(file).trim());
                return;
            }
            final byte[] secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
            java.nio.file.Files.writeString(file, java.util.HexFormat.of().formatHex(secret));
            this.ipSecret = secret;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SpawnArena] Couldn't load ip-secret.key, using a temporary key: " + e.getMessage());
            this.ipSecret = new byte[32];
            new java.security.SecureRandom().nextBytes(this.ipSecret);
        }
    }

    // ── Alt detection ───────────────────────────────────────────────────────

    /** Records both players' current IPs, then checks whether they've ever shared one. */
    public boolean areAlts(final Player a, final Player b) {
        if (a == null || b == null)
            return false;
        recordIp(a.getUniqueId(), a.getAddress() == null ? null : a.getAddress().getAddress());
        recordIp(b.getUniqueId(), b.getAddress() == null ? null : b.getAddress().getAddress());
        return sharesIp(a.getUniqueId(), b.getUniqueId());
    }

    // ── Last win result (shown on the reward hologram instead of chat) ──────

    // Stored in arena_rewards.last_result so it survives restarts; this map is just a cache
    private final java.util.Map<String, String> lastResults = new java.util.concurrent.ConcurrentHashMap<>();

    public void setLastResult(UUID uuid, String arena, String result) {
        lastResults.put(uuid + ":" + arena.toLowerCase(), result);
        // last_reward 0 = "never paid" for a player whose first result here isn't a payout
        final String sql = "INSERT INTO arena_rewards (server_id, uuid, arena, last_reward, last_result) VALUES (" + sid + ", ?, ?, 0, ?) " +
                "ON DUPLICATE KEY UPDATE last_result = VALUES(last_result)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.setString(3, result.length() > 128 ? result.substring(0, 128) : result);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] setLastResult: " + e.getMessage());
        }
    }

    public String getLastResult(UUID uuid, String arena) {
        final String key = uuid + ":" + arena.toLowerCase();
        final String cached = lastResults.get(key);
        if (cached != null)
            return cached;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_result FROM arena_rewards WHERE server_id=" + sid + " AND uuid=? AND arena=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                final String stored = rs.next() ? rs.getString(1) : null;
                final String result = stored == null ? "-" : stored;
                lastResults.put(key, result);
                return result;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getLastResult: " + e.getMessage());
            return "-";
        }
    }

    /** The column holding when a stat last reached its current value. */
    private static String reachedAtColumn(final String field) {
        return switch (field) {
            case "wins" -> "wins_at";
            case "win_streak" -> "streak_at";
            case "best_win_streak" -> "best_at";
            case "elo" -> "elo_at";
            case "ffa_kills" -> "ffa_kills_at";
            case "ffa_best_streak" -> "ffa_best_at";
            default -> null;
        };
    }

    private static boolean hasColumn(final Connection c, final String table, final String column) throws SQLException {
        // H2 stores unquoted identifiers upper-case, MySQL as written
        for (final String t : new String[]{table, table.toUpperCase()}) {
            // This database only: a MySQL server can hold other databases with the same table names
            try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, t, null)) {
                while (rs.next())
                    if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column))
                        return true;
            }
        }
        return false;
    }

    // ── Win reward cooldowns ────────────────────────────────────────────────

    // Countdown holograms read this every second per viewer, so it's served from memory.
    // The TTL only matters when another server shares the same MySQL database.
    private static final long REWARD_CACHE_MILLIS = 60_000;
    /**
     * A player's reward state in one arena: when they were last paid, who that payout was against,
     * and how many payouts in a row they've had against that same opponent.
     */
    public record RewardState(long lastReward, UUID lastOpponent, int opponentPayouts) {}
    private record CachedReward(long fetchedAt, RewardState state) {}
    private final java.util.Map<String, CachedReward> rewardCache = new java.util.concurrent.ConcurrentHashMap<>();

    public RewardState getRewardState(UUID uuid, String arena) {
        final String key = uuid + ":" + arena.toLowerCase();
        final long now = System.currentTimeMillis();
        final CachedReward cached = rewardCache.get(key);
        if (cached != null && now - cached.fetchedAt() < REWARD_CACHE_MILLIS)
            return cached.state();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_reward, last_opponent, opponent_payouts FROM arena_rewards WHERE server_id=" + sid + " AND uuid=? AND arena=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                RewardState state = new RewardState(0L, null, 0);
                if (rs.next()) {
                    final String opponent = rs.getString(2);
                    state = new RewardState(rs.getLong(1), opponent == null ? null : UUID.fromString(opponent), rs.getInt(3));
                }
                rewardCache.put(key, new CachedReward(now, state));
                return state;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getRewardState: " + e.getMessage());
            // Fail closed: treat as just rewarded so a DB hiccup can't be farmed (not cached)
            return new RewardState(now, null, 0);
        }
    }

    /** Epoch millis of the player's last win reward in this arena, or 0 if never. */
    public long getLastReward(UUID uuid, String arena) {
        return getRewardState(uuid, arena).lastReward();
    }

    /** Whether beating this opponent again wouldn't pay: they've already paid out sameOpponentLimit times in a row. */
    public boolean isBlockedAgainst(UUID uuid, String arena, UUID opponent) {
        final int limit = SpawnArena.settings == null ? 0 : SpawnArena.settings.sameOpponentLimit;
        if (limit <= 0 || opponent == null)
            return false;
        final RewardState state = getRewardState(uuid, arena);
        return opponent.equals(state.lastOpponent()) && state.opponentPayouts() >= limit;
    }

    /**
     * Any (non-alt) win against someone other than the last paid opponent clears the same-opponent
     * count, paid or not — the limit only stops farming one specific player.
     */
    public void noteWinAgainst(UUID winner, String arena, UUID loser) {
        if (loser == null)
            return;
        final RewardState state = getRewardState(winner, arena);
        if (state.opponentPayouts() == 0 || loser.equals(state.lastOpponent()))
            return;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE arena_rewards SET last_opponent=?, opponent_payouts=0 WHERE server_id=" + sid + " AND uuid=? AND arena=?")) {
            ps.setString(1, loser.toString());
            ps.setString(2, winner.toString());
            ps.setString(3, arena.toLowerCase());
            ps.executeUpdate();
            rewardCache.put(winner + ":" + arena.toLowerCase(),
                    new CachedReward(System.currentTimeMillis(), new RewardState(state.lastReward(), loser, 0)));
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] noteWinAgainst: " + e.getMessage());
        }
    }

    /** Records a payout; payouts against the same opponent in a row are counted for the same-opponent limit. */
    public void recordPayout(UUID uuid, String arena, long epochMillis, UUID opponent) {
        final RewardState previous = getRewardState(uuid, arena);
        final int payouts = opponent != null && opponent.equals(previous.lastOpponent()) ? previous.opponentPayouts() + 1 : 1;

        final String sql = "INSERT INTO arena_rewards (server_id, uuid, arena, last_reward, last_opponent, opponent_payouts) " +
                "VALUES (" + sid + ", ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE last_reward = VALUES(last_reward), " +
                "last_opponent = VALUES(last_opponent), opponent_payouts = VALUES(opponent_payouts)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.setLong(3, epochMillis);
            ps.setString(4, opponent == null ? null : opponent.toString());
            ps.setInt(5, payouts);
            ps.executeUpdate();
            rewardCache.put(uuid + ":" + arena.toLowerCase(),
                    new CachedReward(System.currentTimeMillis(), new RewardState(epochMillis, opponent, payouts)));
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordPayout: " + e.getMessage());
        }
    }

    /** Clears a player's win reward cooldown for one arena, or every arena when {@code arena} is null. */
    public int clearRewardCooldown(UUID uuid, String arena) {
        final String sql = arena == null
                ? "DELETE FROM arena_rewards WHERE server_id=" + sid + " AND uuid=?"
                : "DELETE FROM arena_rewards WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (arena != null)
                ps.setString(2, arena.toLowerCase());
            return ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] clearRewardCooldown: " + e.getMessage());
            return -1;
        } finally {
            final String prefix = uuid + ":";
            rewardCache.keySet().removeIf(key -> key.startsWith(prefix)
                    && (arena == null || key.equals(prefix + arena.toLowerCase())));
            lastResults.keySet().removeIf(key -> key.startsWith(prefix)
                    && (arena == null || key.equals(prefix + arena.toLowerCase())));
        }
    }

    /**
     * Whether this player is THE #1 current win streak holder in the arena. Exactly one player at a time:
     * on a tie the one who reached that streak first keeps it. A streak of 0 never counts.
     * Uses the cached leaderboard, so it's cheap enough for per-second hologram placeholders.
     */
    public boolean isStreakLeader(UUID uuid, String arena) {
        return streakRank(uuid, arena) == 1;
    }

    /**
     * This player's place (1, 2 or 3) on the arena's current win streak board, or 0 if they're not on the podium.
     * On a tie, whoever got there first is higher.
     */
    public int streakRank(UUID uuid, String arena) {
        final List<LeaderboardEntry> top = getTopPlayersCached(arena, "win_streak",
                me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT);
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            if (top.get(i).value() < 1)
                return 0;
            if (top.get(i).uuid().equals(uuid))
                return i + 1;
        }
        return 0;
    }

    public double rewardAmountFor(UUID uuid, Arena arena) {
        return arena.getWinReward() * arena.rewardMultiplierFor(streakRank(uuid, arena.getArenaName()));
    }

    public int rewardCooldownMinutesFor(UUID uuid, Arena arena) {
        return arena.rewardCooldownMinutesFor(streakRank(uuid, arena.getArenaName()));
    }

    /** Milliseconds until this player can earn the arena's win reward again (0 = ready now). */
    public long rewardCooldownRemaining(UUID uuid, Arena arena) {
        final long cooldown = rewardCooldownMinutesFor(uuid, arena) * 60_000L;
        final long remaining = getLastReward(uuid, arena.getArenaName()) + cooldown - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /** "1h 05m 09s", "4m 09s", "9s". */
    public static String formatCountdown(final long millis) {
        final long totalSeconds = (millis + 999) / 1000;
        final long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return s + "s";
    }

    private static volatile String[] detectedCurrency;

    /** "$500", "$5k", "$1.5k", "$2M" — using the configured or economy-detected currency symbol. */
    public static String formatMoney(final double amount) {
        final double abs = Math.abs(amount);
        final String number;
        if (abs >= 1_000_000_000) number = oneDecimal(amount / 1_000_000_000) + "B";
        else if (abs >= 1_000_000) number = oneDecimal(amount / 1_000_000) + "M";
        else if (abs >= 1_000) number = oneDecimal(amount / 1_000) + "k";
        else number = oneDecimal(amount);

        final String[] affixes = currencyAffixes();
        return affixes[0] + number + affixes[1];
    }

    private static String[] currencyAffixes() {
        final String configured = SpawnArena.settings == null ? null : SpawnArena.settings.currencySymbol;
        if (configured != null && !configured.isEmpty())
            return new String[]{configured, ""};

        if (detectedCurrency != null)
            return detectedCurrency;

        // WinRewards links against Vault, so only touch it when Vault is there
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            final String[] detected = WinRewards.currencyAffixes();
            if (detected != null) {
                detectedCurrency = detected;
                return detected;
            }
        }
        return new String[]{"$", ""};
    }

    private static String oneDecimal(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** "2h", "1h 30m", "45m". */
    public static String formatMinutes(final int minutes) {
        final int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "m";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public int getWins(UUID uuid, String arena) {
        return getField(uuid, arena, "wins");
    }

    public int getWinStreak(UUID uuid, String arena) {
        return getField(uuid, arena, "win_streak");
    }

    public int getLosses(UUID uuid, String arena) {
        return getField(uuid, arena, "losses");
    }

    /** When losses started being counted on this server, if there were stats from before then (else 0). */
    public long getLossesSince() {
        try (Connection c = dataSource.getConnection()) {
            final String v = getMeta(c, "losses_since");
            return v == null ? 0 : Long.parseLong(v);
        } catch (SQLException | NumberFormatException e) {
            Bukkit.getLogger().warning("[SpawnArena] getLossesSince: " + e.getMessage());
            return 0;
        }
    }

    private String getMeta(final Connection c, final String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM arena_meta WHERE server_id=" + sid + " AND meta_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Sets a value unless it's already there (works the same on H2 and MySQL). */
    private void setMeta(final Connection c, final String key, final String value) throws SQLException {
        if (getMeta(c, key) != null)
            return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO arena_meta (server_id, meta_key, meta_value) VALUES (" + sid + ", ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public static final int START_ELO = 1000;
    private static final int ELO_K = 32;

    /** Their Elo rating in this arena (START_ELO if they've never had a counted fight there). */
    public int getElo(UUID uuid, String arena) {
        final String sql = "SELECT elo FROM arena_stats WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getElo: " + e.getMessage());
        }
        return START_ELO;
    }

    /**
     * Updates everyone's Elo after a counted fight and returns each player's change. Each is rated against the
     * other side's average (so 2v2 works like 1v1): beating someone rated higher gains more, at most ELO_K.
     */
    public java.util.Map<UUID, int[]> recordElo(final List<Player> winners, final List<Player> losers, final String arena) {
        final java.util.Map<UUID, Integer> before = new java.util.HashMap<>();
        for (final Player p : winners) before.put(p.getUniqueId(), getElo(p.getUniqueId(), arena));
        for (final Player p : losers) before.put(p.getUniqueId(), getElo(p.getUniqueId(), arena));
        final double winnersAvg = winners.stream().mapToInt(p -> before.get(p.getUniqueId())).average().orElse(START_ELO);
        final double losersAvg = losers.stream().mapToInt(p -> before.get(p.getUniqueId())).average().orElse(START_ELO);

        final java.util.Map<UUID, int[]> result = new java.util.LinkedHashMap<>(); // uuid -> {new rating, change}
        for (final Player p : winners) {
            final int r = before.get(p.getUniqueId());
            final int change = Math.max(1, (int) Math.round(ELO_K * (1 - expected(r, losersAvg))));
            result.put(p.getUniqueId(), new int[]{r + change, change});
        }
        for (final Player p : losers) {
            final int r = before.get(p.getUniqueId());
            final int change = -Math.min(r, Math.max(1, (int) Math.round(ELO_K * expected(r, winnersAvg))));
            result.put(p.getUniqueId(), new int[]{r + change, change});
        }
        for (final java.util.Map.Entry<UUID, int[]> e : result.entrySet())
            setField(e.getKey(), arena, "elo", e.getValue()[0]);
        return result;
    }

    /** Chance (0..1) a player rated {@code rating} beats one rated {@code opponent}. */
    private static double expected(final double rating, final double opponent) {
        return 1 / (1 + Math.pow(10, (opponent - rating) / 400));
    }

    public int getBestWinStreak(UUID uuid, String arena) {
        return getField(uuid, arena, "best_win_streak");
    }

    public int getField(UUID uuid, String arena, String field) {
        final String sql = "SELECT " + field + " FROM arena_stats WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getField(" + field + "): " + e.getMessage());
            return 0;
        }
    }

    // ── Write ───────────────────────────────────────────────────────────────

    /** Records a win. Returns the new win streak. */
    public int recordWin(Player player, String arena) {
        return recordWin(player, arena, true);
    }

    /** Records a win; with {@code raiseStreak} false it counts as a win but leaves the streak as it is. Returns the streak. */
    public int recordWin(Player player, String arena, boolean raiseStreak) {
        if (!raiseStreak)
            return recordWinWithoutStreak(player, arena);
        ensure(player.getUniqueId(), arena);
        // MySQL applies SET assignments left to right (later ones see earlier results), H2 doesn't,
        // so everything that reads the old win_streak / best_win_streak comes before they're changed.
        final String sql = "UPDATE arena_stats SET " +
                "best_at = CASE WHEN win_streak + 1 > best_win_streak THEN ? ELSE best_at END, " +
                "best_win_streak = GREATEST(best_win_streak, win_streak + 1), " +
                "wins = wins + 1, " +
                "wins_at = ?, " +
                "streak_at = ?, " +
                "win_streak = win_streak + 1 " +
                "WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        final long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setString(4, player.getUniqueId().toString());
            ps.setString(5, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordWin: " + e.getMessage());
        }
        invalidateLeaderboards();
        return getWinStreak(player.getUniqueId(), arena);
    }

    private int recordWinWithoutStreak(final Player player, final String arena) {
        ensure(player.getUniqueId(), arena);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE arena_stats SET wins = wins + 1, wins_at = ? WHERE server_id=" + sid + " AND uuid=? AND arena=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, player.getUniqueId().toString());
            ps.setString(3, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordWin: " + e.getMessage());
        }
        invalidateLeaderboards();
        return getWinStreak(player.getUniqueId(), arena);
    }

    /**
     * Notes a win against {@code opponent} (a player, or a 2v2 pair's key) and returns how many wins in a row that is
     * against them: 1 for someone new.
     */
    public int noteStreakOpponent(final UUID winner, final String arena, final UUID opponent) {
        ensure(winner, arena);
        final String sql = "UPDATE arena_stats SET streak_opponent_wins = CASE WHEN streak_opponent = ? THEN streak_opponent_wins + 1 ELSE 1 END, "
                + "streak_opponent = ? WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, opponent.toString());
            ps.setString(2, opponent.toString());
            ps.setString(3, winner.toString());
            ps.setString(4, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] noteStreakOpponent: " + e.getMessage());
            return 1;
        }
        return getField(winner, arena, "streak_opponent_wins");
    }

    /** Ends their streak, keeping what it was (if they had one) as their last lost streak. */
    public void recordLoss(Player player, String arena) {
        ensure(player.getUniqueId(), arena);
        // The streak is read before it's zeroed: lost_streak and lost_at come first (MySQL applies SETs in order)
        final String sql = "UPDATE arena_stats SET lost_streak = CASE WHEN win_streak > 0 THEN win_streak ELSE lost_streak END, "
                + "lost_at = CASE WHEN win_streak > 0 THEN ? ELSE lost_at END, win_streak = 0, streak_at = ?, losses = losses + 1, "
                + "streak_opponent = NULL, streak_opponent_wins = 0 "
                + "WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        final long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, player.getUniqueId().toString());
            ps.setString(4, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordLoss: " + e.getMessage());
        }
        invalidateLeaderboards();
    }

    // ── Free-for-all ────────────────────────────────────────────────────────

    static final String[] FFA_COLUMNS = {"ffa_kills", "ffa_deaths", "ffa_best_streak", "ffa_kills_at", "ffa_best_at"};

    /** A free-for-all kill; {@code streak} is their kill streak now, counting this one. Call off the main thread. */
    public void recordFfaKill(final UUID uuid, final String arena, final int streak) {
        ensure(uuid, arena);
        // The best streak (and when it was reached) is read before it's raised: MySQL applies SETs in order
        final String sql = "UPDATE arena_stats SET ffa_best_at = CASE WHEN ? > ffa_best_streak THEN ? ELSE ffa_best_at END, "
                + "ffa_best_streak = GREATEST(ffa_best_streak, ?), ffa_kills = ffa_kills + 1, ffa_kills_at = ? "
                + "WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        final long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, streak);
            ps.setLong(2, now);
            ps.setInt(3, streak);
            ps.setLong(4, now);
            ps.setString(5, uuid.toString());
            ps.setString(6, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordFfaKill: " + e.getMessage());
        }
        invalidateLeaderboards();
    }

    /** A free-for-all death. Call off the main thread. */
    public void recordFfaDeath(final UUID uuid, final String arena) {
        ensure(uuid, arena);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE arena_stats SET ffa_deaths = ffa_deaths + 1 WHERE server_id=" + sid + " AND uuid=? AND arena=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] recordFfaDeath: " + e.getMessage());
        }
        invalidateLeaderboards();
    }

    /** The streak they had when they last lost one (streak 0 if they never have), and when. */
    public record LostStreak(int streak, long at) {}

    public LostStreak getLostStreak(UUID uuid, String arena) {
        final String sql = "SELECT lost_streak, lost_at FROM arena_stats WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return new LostStreak(rs.getInt("lost_streak"), rs.getLong("lost_at"));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getLostStreak: " + e.getMessage());
        }
        return new LostStreak(0, 0);
    }

    /** Upserts a single stat field. */
    public void setField(UUID uuid, String arena, String field, int value) {
        ensure(uuid, arena);
        // A changed value counts as reached now, for leaderboard tie-breaks
        final String reachedAt = reachedAtColumn(field);
        final String sql = "UPDATE arena_stats SET " + field + "=?" + (reachedAt != null ? ", " + reachedAt + "=?" : "")
                + " WHERE server_id=" + sid + " AND uuid=? AND arena=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, value);
            if (reachedAt != null)
                ps.setLong(i++, System.currentTimeMillis());
            ps.setString(i++, uuid.toString());
            ps.setString(i, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] setField(" + field + "): " + e.getMessage());
        }
        invalidateLeaderboards();
    }

    // ── Leaderboard cache ───────────────────────────────────────────────────

    // Writes through this plugin clear the cache immediately; the TTL only covers edits made
    // directly in the database (e.g. another server sharing the same MySQL).
    private static final long TOP_CACHE_MILLIS = 30_000;

    private record CachedTop(long fetchedAt, List<LeaderboardEntry> entries) {}
    private final java.util.Map<String, CachedTop> topCache = new java.util.concurrent.ConcurrentHashMap<>();

    public List<LeaderboardEntry> getTopPlayersCached(String arena, String field, int limit) {
        final String key = arena.toLowerCase() + ":" + field + ":" + limit;
        final long now = System.currentTimeMillis();
        final CachedTop cached = topCache.get(key);
        if (cached != null && now - cached.fetchedAt() < TOP_CACHE_MILLIS)
            return cached.entries();

        final List<LeaderboardEntry> fresh = getTopPlayers(arena, field, limit);
        topCache.put(key, new CachedTop(now, fresh));
        return fresh;
    }

    public void invalidateLeaderboards() {
        topCache.clear();
        // Kept (so nobody sees a blank), just refreshed on their next look
        standingCache.replaceAll((key, cached) -> new CachedStanding(0, cached.standing()));
    }

    /** A player's place on a leaderboard (in the board's own order) and their value; rank 0 = not on it at all. */
    public record Standing(int rank, int value) {}
    private record CachedStanding(long fetchedAt, Standing standing) {}
    private final java.util.Map<String, CachedStanding> standingCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> standingLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Never queries the database on the calling thread (holograms resolve this for every viewer): returns what's
     * cached, null the very first time, and refreshes it in the background once it's as old as the leaderboards.
     */
    public Standing getStandingCached(final UUID uuid, final String arena, final String field) {
        final String key = uuid + ":" + arena.toLowerCase() + ":" + field;
        final CachedStanding cached = standingCache.get(key);
        if ((cached == null || System.currentTimeMillis() - cached.fetchedAt() >= TOP_CACHE_MILLIS) && standingLoading.add(key)) {
            Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
                try {
                    final Standing fresh = fetchStanding(uuid, arena, field);
                    if (fresh != null)
                        standingCache.put(key, new CachedStanding(System.currentTimeMillis(), fresh));
                } finally {
                    standingLoading.remove(key);
                }
            });
        }
        return cached == null ? null : cached.standing();
    }

    /** Drops a player's cached leaderboard places (when they leave), so the cache doesn't grow forever. */
    public void forgetStandings(final UUID uuid) {
        final String prefix = uuid + ":";
        standingCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Counts everyone the board puts ahead of them: higher value, or the same value reached earlier (then by uuid). */
    private Standing fetchStanding(final UUID uuid, final String arena, final String field) {
        // Same order as the board: value, then who got there first (where that's kept), then uuid
        final String reachedAt = reachedAtColumn(field);
        final String tie = reachedAt == null ? "o.uuid < s.uuid"
                : "(o." + reachedAt + " < s." + reachedAt + " OR (o." + reachedAt + " = s." + reachedAt + " AND o.uuid < s.uuid))";
        final String played = field.startsWith("ffa_") ? " AND (o.ffa_kills > 0 OR o.ffa_deaths > 0)" : "";
        final String sql = "SELECT s." + field + " AS v, (SELECT COUNT(*) FROM arena_stats o WHERE o.server_id=" + sid
                + " AND o.arena=s.arena" + played + " AND (o." + field + " > s." + field + " OR (o." + field + " = s." + field
                + " AND " + tie + "))) AS ahead FROM arena_stats s WHERE s.server_id=" + sid + " AND s.arena=? AND s.uuid=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arena.toLowerCase());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Standing(rs.getInt("ahead") + 1, rs.getInt("v")) : new Standing(0, 0);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getStanding: " + e.getMessage());
            return null;
        }
    }

    /** Inserts a default row if none exists. */
    private void ensure(UUID uuid, String arena) {
        // INSERT IGNORE works on MySQL and on H2 in MODE=MySQL
        final String sql = "INSERT IGNORE INTO arena_stats (server_id, uuid, arena, wins, win_streak, best_win_streak) " +
                "VALUES (" + sid + ", ?, ?, 0, 0, 0)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] ensure: " + e.getMessage());
        }
    }

    // ── Leaderboard ─────────────────────────────────────────────────────────

    public record LeaderboardEntry(UUID uuid, String name, int value) {}

    /**
     * Returns up to {@code limit} players ranked by the given field.
     * {@code field} must be one of: wins, win_streak, best_win_streak.
     */
    public List<LeaderboardEntry> getTopPlayers(String arena, String field, int limit) {
        final List<LeaderboardEntry> entries = new ArrayList<>();
        // Ties: whoever reached that value first ranks higher, so there's always exactly one #1
        final String reachedAt = reachedAtColumn(field);
        final String tieBreak = reachedAt != null ? ", " + reachedAt + " ASC" : "";
        final String played = field.startsWith("ffa_") ? " AND (ffa_kills > 0 OR ffa_deaths > 0)" : "";
        final String sql = "SELECT uuid, " + field + " FROM arena_stats " +
                "WHERE server_id=" + sid + " AND arena=?" + played + " ORDER BY " + field + " DESC" + tieBreak + ", uuid ASC LIMIT ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arena.toLowerCase());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final UUID uuid = UUID.fromString(rs.getString("uuid"));
                    final int value = rs.getInt(field);
                    // Resolve display name via Bukkit (offline-safe)
                    final String name = Bukkit.getOfflinePlayer(uuid).getName();
                    entries.add(new LeaderboardEntry(uuid, name != null ? name : uuid.toString(), value));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getTopPlayers: " + e.getMessage());
        }
        return entries;
    }

    // ── Migration ───────────────────────────────────────────────────────────

    /**
     * DB_CLOSE_DELAY=-1 keeps the database open while the plugin runs, even with no connection open; it's only
     * closed by the SHUTDOWN in {@link #close}. Without it H2 closed and reopened the file whenever the pool
     * briefly had no connections, and on a test server one such reopen came back with only some of the tables.
     */
    private String h2Url() {
        return h2FileUrl() + ";DB_CLOSE_DELAY=-1";
    }

    /** For a one-off look at the file (migrating from it): closes again with its last connection. */
    private String h2FileUrl() {
        return "jdbc:h2:" + h2File.getAbsolutePath() + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
    }

    public boolean isMySql() {
        return mysql;
    }

    public boolean h2DatabaseExists() {
        return new File(h2File.getParentFile(), h2File.getName() + ".mv.db").exists();
    }

    /**
     * Copies every row from the local H2 file into the configured MySQL database.
     * Rows that already exist in MySQL are overwritten. Returns the number of rows copied.
     */
    public int migrateH2ToMySql() throws SQLException {
        if (!mysql)
            throw new IllegalStateException("Database type is not MYSQL");

        final java.util.Properties props = new java.util.Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");

        final String upsert = "INSERT INTO arena_stats (server_id, uuid, arena, wins, win_streak, best_win_streak, streak_at, wins_at, best_at, lost_streak, lost_at, elo, elo_at, losses, ffa_kills, ffa_deaths, ffa_best_streak, ffa_kills_at, ffa_best_at) " +
                "VALUES (" + sid + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                "wins = VALUES(wins), win_streak = VALUES(win_streak), best_win_streak = VALUES(best_win_streak), " +
                "streak_at = VALUES(streak_at), wins_at = VALUES(wins_at), best_at = VALUES(best_at), " +
                "lost_streak = VALUES(lost_streak), lost_at = VALUES(lost_at), elo = VALUES(elo), elo_at = VALUES(elo_at), losses = VALUES(losses), " +
                "ffa_kills = VALUES(ffa_kills), ffa_deaths = VALUES(ffa_deaths), ffa_best_streak = VALUES(ffa_best_streak), " +
                "ffa_kills_at = VALUES(ffa_kills_at), ffa_best_at = VALUES(ffa_best_at)";

        int copied = 0;
        try (Connection h2 = new org.h2.Driver().connect(h2FileUrl() + ";IFEXISTS=TRUE", props);
             Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, wins, win_streak, best_win_streak"
                     + reachedAtSelect(h2, "streak_at") + reachedAtSelect(h2, "wins_at") + reachedAtSelect(h2, "best_at")
                     + reachedAtSelect(h2, "lost_streak") + reachedAtSelect(h2, "lost_at")
                     + (hasColumn(h2, "arena_stats", "elo") ? ", elo" : ", " + START_ELO + " AS elo") + reachedAtSelect(h2, "elo_at") + reachedAtSelect(h2, "losses")
                     + reachedAtSelect(h2, "ffa_kills") + reachedAtSelect(h2, "ffa_deaths") + reachedAtSelect(h2, "ffa_best_streak")
                     + reachedAtSelect(h2, "ffa_kills_at") + reachedAtSelect(h2, "ffa_best_at")
                     + " FROM arena_stats");
             Connection target = dataSource.getConnection()) {

            target.setAutoCommit(false);
            try (PreparedStatement ps = target.prepareStatement(upsert)) {
                while (rs.next()) {
                    ps.setString(1, rs.getString("uuid"));
                    ps.setString(2, rs.getString("arena"));
                    ps.setInt(3, rs.getInt("wins"));
                    ps.setInt(4, rs.getInt("win_streak"));
                    ps.setInt(5, rs.getInt("best_win_streak"));
                    ps.setLong(6, rs.getLong("streak_at"));
                    ps.setLong(7, rs.getLong("wins_at"));
                    ps.setLong(8, rs.getLong("best_at"));
                    ps.setInt(9, rs.getInt("lost_streak"));
                    ps.setLong(10, rs.getLong("lost_at"));
                    ps.setInt(11, rs.getInt("elo"));
                    ps.setLong(12, rs.getLong("elo_at"));
                    ps.setInt(13, rs.getInt("losses"));
                    ps.setInt(14, rs.getInt("ffa_kills"));
                    ps.setInt(15, rs.getInt("ffa_deaths"));
                    ps.setInt(16, rs.getInt("ffa_best_streak"));
                    ps.setLong(17, rs.getLong("ffa_kills_at"));
                    ps.setLong(18, rs.getLong("ffa_best_at"));
                    ps.addBatch();
                    if (++copied % 500 == 0)
                        ps.executeBatch();
                }
                ps.executeBatch();
                copyRewardCooldowns(h2, target, sid);
                copyPayoutLog(h2, target, sid);
                copyBetBonuses(h2, target, sid);
                copyAuditLog(h2, target, sid);
                copyOwedItems(h2, target, sid);
                if (copied > 0)
                    copyLossesSince(h2, target);
                target.commit();
                rewardCache.clear();
            } catch (SQLException e) {
                target.rollback();
                throw e;
            } finally {
                target.setAutoCommit(true);
            }
        }
        invalidateLeaderboards();
        return copied;
    }

    /**
     * The copied stats' losses were counted from whenever the H2 file started counting them: from its own
     * "since" date, from now if it's older than losses (none were stored), or from the start (no note).
     */
    private void copyLossesSince(final Connection h2, final Connection target) throws SQLException {
        String since = null;
        if (!hasColumn(h2, "arena_stats", "losses"))
            since = String.valueOf(System.currentTimeMillis());
        else {
            try (ResultSet tables = h2.getMetaData().getTables(null, null, "ARENA_META", null)) {
                if (tables.next()) {
                    try (Statement read = h2.createStatement();
                         ResultSet rs = read.executeQuery("SELECT meta_value FROM arena_meta WHERE meta_key='losses_since'")) {
                        if (rs.next())
                            since = rs.getString(1);
                    }
                }
            }
        }
        if (since == null)
            return;
        try (PreparedStatement del = target.prepareStatement("DELETE FROM arena_meta WHERE server_id=" + sid + " AND meta_key='losses_since'")) {
            del.executeUpdate();
        }
        setMeta(target, "losses_since", since);
    }

    /** H2 files from older versions may lack a "reached at" column; read those as 0. */
    private static String reachedAtSelect(final Connection h2, final String column) throws SQLException {
        return hasColumn(h2, "arena_stats", column) ? ", " + column : ", 0 AS " + column;
    }

    /** Appends the H2 bet bonus log to MySQL's (like the payout log). */
    private static void copyBetBonuses(final Connection h2, final Connection target, final String sid) throws SQLException {
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "ARENA_BET_BONUSES", null)) {
            if (!tables.next())
                return;
        }
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, amount, paid_at FROM arena_bet_bonuses ORDER BY id");
             PreparedStatement ps = target.prepareStatement("INSERT INTO arena_bet_bonuses (server_id, uuid, arena, amount, paid_at) VALUES (" + sid + ", ?, ?, ?, ?)")) {
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setString(2, rs.getString("arena"));
                ps.setDouble(3, rs.getDouble("amount"));
                ps.setLong(4, rs.getLong("paid_at"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Appends the H2 payout log to MySQL's (running the migration twice would copy it twice). */
    private static void copyPayoutLog(final Connection h2, final Connection target, final String sid) throws SQLException {
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "ARENA_PAYOUTS", null)) {
            if (!tables.next())
                return;
        }
        final boolean hasOpponent2 = hasColumn(h2, "arena_payouts", "opponent2");
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, amount, opponent, "
                     + (hasOpponent2 ? "opponent2" : "NULL AS opponent2") + ", leader, paid_at FROM arena_payouts");
             PreparedStatement ps = target.prepareStatement(
                     "INSERT INTO arena_payouts (server_id, uuid, arena, amount, opponent, opponent2, leader, paid_at) VALUES (" + sid + ", ?, ?, ?, ?, ?, ?, ?)")) {
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setString(2, rs.getString("arena"));
                ps.setDouble(3, rs.getDouble("amount"));
                ps.setString(4, rs.getString("opponent"));
                ps.setString(5, rs.getString("opponent2"));
                ps.setBoolean(6, rs.getBoolean("leader"));
                ps.setLong(7, rs.getLong("paid_at"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Items still owed to players who logged out mid-fight. */
    private static void copyOwedItems(final Connection h2, final Connection target, final String sid) throws SQLException {
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "OWED_ITEMS", null)) {
            if (!tables.next())
                return;
        }
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, item, owed_at FROM owed_items ORDER BY id");
             PreparedStatement ps = target.prepareStatement("INSERT INTO owed_items (server_id, uuid, item, owed_at) VALUES (" + sid + ", ?, ?, ?)")) {
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setBytes(2, rs.getBytes("item"));
                ps.setLong(3, rs.getLong("owed_at"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Appends the H2 staff stat-change log to MySQL's. */
    private static void copyAuditLog(final Connection h2, final Connection target, final String sid) throws SQLException {
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "STAT_AUDIT", null)) {
            if (!tables.next())
                return;
        }
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT actor, actor_name, target, arena, field, old_value, new_value, "
                     + "action, changed_at, rolled_back FROM stat_audit ORDER BY id");
             PreparedStatement ps = target.prepareStatement("INSERT INTO stat_audit (server_id, actor, actor_name, target, arena, "
                     + "field, old_value, new_value, action, changed_at, rolled_back) VALUES (" + sid + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            while (rs.next()) {
                ps.setString(1, rs.getString("actor"));
                ps.setString(2, rs.getString("actor_name"));
                ps.setString(3, rs.getString("target"));
                ps.setString(4, rs.getString("arena"));
                ps.setString(5, rs.getString("field"));
                ps.setObject(6, rs.getObject("old_value"), java.sql.Types.INTEGER);
                ps.setObject(7, rs.getObject("new_value"), java.sql.Types.INTEGER);
                ps.setString(8, rs.getString("action"));
                ps.setLong(9, rs.getLong("changed_at"));
                ps.setBoolean(10, rs.getBoolean("rolled_back"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void copyRewardCooldowns(final Connection h2, final Connection target, final String sid) throws SQLException {
        // H2 files from before win rewards existed don't have this table
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "ARENA_REWARDS", null)) {
            if (!tables.next())
                return;
        }

        // Target is MySQL, which applies SET assignments left to right: compare against the old
        // last_reward before it's overwritten, so the newer row's opponent data wins
        final String upsert = "INSERT INTO arena_rewards (server_id, uuid, arena, last_reward, last_opponent, opponent_payouts, last_result) " +
                "VALUES (" + sid + ", ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                "last_opponent = IF(VALUES(last_reward) >= last_reward, VALUES(last_opponent), last_opponent), " +
                "opponent_payouts = IF(VALUES(last_reward) >= last_reward, VALUES(opponent_payouts), opponent_payouts), " +
                "last_result = IF(VALUES(last_reward) >= last_reward, VALUES(last_result), last_result), " +
                "last_reward = GREATEST(last_reward, VALUES(last_reward))";
        final boolean hasOpponents = hasColumn(h2, "arena_rewards", "last_opponent");
        final boolean hasLastResult = hasColumn(h2, "arena_rewards", "last_result");
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, last_reward"
                     + (hasOpponents ? ", last_opponent, opponent_payouts" : ", NULL AS last_opponent, 0 AS opponent_payouts")
                     + (hasLastResult ? ", last_result" : ", NULL AS last_result")
                     + " FROM arena_rewards");
             PreparedStatement ps = target.prepareStatement(upsert)) {
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setString(2, rs.getString("arena"));
                ps.setLong(3, rs.getLong("last_reward"));
                ps.setString(4, rs.getString("last_opponent"));
                ps.setInt(5, rs.getInt("opponent_payouts"));
                ps.setString(6, rs.getString("last_result"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void close() {
        if (dataSource == null || dataSource.isClosed())
            return;

        // Hikari closes connections on its own thread, after onDisable has returned and the plugin's
        // jar is closed; H2 would then fail to load the classes it needs to close the file cleanly.
        // Shut H2 down here, on the disabling thread, while the jar is still open.
        if (!mysql) {
            boolean shutDown = false;
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("SHUTDOWN");
                shutDown = true;
            } catch (SQLException e) {
                // After a successful SHUTDOWN, closing the connection always reports the database as already closed
                if (!shutDown)
                    Bukkit.getLogger().warning("[SpawnArena] H2 shutdown: " + e.getMessage());
            }
        }

        dataSource.close();
    }
}
