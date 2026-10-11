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

    public StatsManager(SpawnArena plugin, DatabaseConfig config) {
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
        }

        this.dataSource = new HikariDataSource(hc);
        createTable();
    }

    private void createTable() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS arena_stats (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "wins INT NOT NULL DEFAULT 0, " +
                    "win_streak INT NOT NULL DEFAULT 0, " +
                    "best_win_streak INT NOT NULL DEFAULT 0, " +
                    "streak_at BIGINT NOT NULL DEFAULT 0, " +
                    "wins_at BIGINT NOT NULL DEFAULT 0, " +
                    "best_at BIGINT NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (uuid, arena))");
            // Tables created before the "reached at" columns existed
            for (final String column : new String[]{"streak_at", "wins_at", "best_at"})
                if (!hasColumn(c, "arena_stats", column))
                    s.execute("ALTER TABLE arena_stats ADD COLUMN " + column + " BIGINT NOT NULL DEFAULT 0");
            s.execute("CREATE TABLE IF NOT EXISTS arena_rewards (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "arena VARCHAR(64) NOT NULL, " +
                    "last_reward BIGINT NOT NULL, " +
                    "PRIMARY KEY (uuid, arena))");
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[SpawnArena] Failed to create tables: " + e.getMessage());
        }
    }

    /** The column holding when a stat last reached its current value. */
    private static String reachedAtColumn(final String field) {
        return switch (field) {
            case "wins" -> "wins_at";
            case "win_streak" -> "streak_at";
            case "best_win_streak" -> "best_at";
            default -> null;
        };
    }

    private static boolean hasColumn(final Connection c, final String table, final String column) throws SQLException {
        // H2 stores unquoted identifiers upper-case, MySQL as written
        for (final String t : new String[]{table, table.toUpperCase()}) {
            try (ResultSet rs = c.getMetaData().getColumns(null, null, t, null)) {
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
    private record CachedReward(long fetchedAt, long lastReward) {}
    private final java.util.Map<String, CachedReward> rewardCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Epoch millis of the player's last win reward in this arena, or 0 if never. */
    public long getLastReward(UUID uuid, String arena) {
        final String key = uuid + ":" + arena.toLowerCase();
        final long now = System.currentTimeMillis();
        final CachedReward cached = rewardCache.get(key);
        if (cached != null && now - cached.fetchedAt() < REWARD_CACHE_MILLIS)
            return cached.lastReward();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_reward FROM arena_rewards WHERE uuid=? AND arena=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                final long last = rs.next() ? rs.getLong(1) : 0L;
                rewardCache.put(key, new CachedReward(now, last));
                return last;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] getLastReward: " + e.getMessage());
            // Fail closed: treat as just rewarded so a DB hiccup can't be farmed (not cached)
            return now;
        }
    }

    /** Clears a player's win reward cooldown for one arena, or every arena when {@code arena} is null. */
    public int clearRewardCooldown(UUID uuid, String arena) {
        final String sql = arena == null
                ? "DELETE FROM arena_rewards WHERE uuid=?"
                : "DELETE FROM arena_rewards WHERE uuid=? AND arena=?";
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
        }
    }

    /**
     * Whether this player is THE #1 current win streak holder in the arena. Exactly one player at a time:
     * on a tie the one who reached that streak first keeps it. A streak of 0 never counts.
     * Uses the cached leaderboard, so it's cheap enough for per-second hologram placeholders.
     */
    public boolean isStreakLeader(UUID uuid, String arena) {
        final List<LeaderboardEntry> top = getTopPlayersCached(arena, "win_streak",
                me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT);
        return !top.isEmpty() && top.get(0).value() > 0 && top.get(0).uuid().equals(uuid);
    }

    public double rewardAmountFor(UUID uuid, Arena arena) {
        final double base = arena.getWinReward();
        return isStreakLeader(uuid, arena.getArenaName()) ? base * arena.getLeaderRewardMultiplier() : base;
    }

    public int rewardCooldownMinutesFor(UUID uuid, Arena arena) {
        return isStreakLeader(uuid, arena.getArenaName())
                ? arena.getLeaderRewardCooldownMinutes()
                : arena.getWinRewardCooldownMinutes();
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

    public void setLastReward(UUID uuid, String arena, long epochMillis) {
        final String sql = "INSERT INTO arena_rewards (uuid, arena, last_reward) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE last_reward = VALUES(last_reward)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, arena.toLowerCase());
            ps.setLong(3, epochMillis);
            ps.executeUpdate();
            rewardCache.put(uuid + ":" + arena.toLowerCase(), new CachedReward(System.currentTimeMillis(), epochMillis));
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[SpawnArena] setLastReward: " + e.getMessage());
        }
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public int getWins(UUID uuid, String arena) {
        return getField(uuid, arena, "wins");
    }

    public int getWinStreak(UUID uuid, String arena) {
        return getField(uuid, arena, "win_streak");
    }

    public int getBestWinStreak(UUID uuid, String arena) {
        return getField(uuid, arena, "best_win_streak");
    }

    private int getField(UUID uuid, String arena, String field) {
        final String sql = "SELECT " + field + " FROM arena_stats WHERE uuid=? AND arena=?";
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
                "WHERE uuid=? AND arena=?";
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

    public void recordLoss(Player player, String arena) {
        ensure(player.getUniqueId(), arena);
        setField(player.getUniqueId(), arena, "win_streak", 0);
    }

    /** Upserts a single stat field. */
    public void setField(UUID uuid, String arena, String field, int value) {
        ensure(uuid, arena);
        // A changed value counts as reached now, for leaderboard tie-breaks
        final String reachedAt = reachedAtColumn(field);
        final String sql = "UPDATE arena_stats SET " + field + "=?" + (reachedAt != null ? ", " + reachedAt + "=?" : "")
                + " WHERE uuid=? AND arena=?";
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
    }

    /** Inserts a default row if none exists. */
    private void ensure(UUID uuid, String arena) {
        // INSERT IGNORE works on MySQL and on H2 in MODE=MySQL
        final String sql = "INSERT IGNORE INTO arena_stats (uuid, arena, wins, win_streak, best_win_streak) " +
                "VALUES (?, ?, 0, 0, 0)";
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
        final String sql = "SELECT uuid, " + field + " FROM arena_stats " +
                "WHERE arena=? ORDER BY " + field + " DESC" + tieBreak + ", uuid ASC LIMIT ?";
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

    private String h2Url() {
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

        final String upsert = "INSERT INTO arena_stats (uuid, arena, wins, win_streak, best_win_streak, streak_at, wins_at, best_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                "wins = VALUES(wins), win_streak = VALUES(win_streak), best_win_streak = VALUES(best_win_streak), " +
                "streak_at = VALUES(streak_at), wins_at = VALUES(wins_at), best_at = VALUES(best_at)";

        int copied = 0;
        try (Connection h2 = new org.h2.Driver().connect(h2Url() + ";IFEXISTS=TRUE", props);
             Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, wins, win_streak, best_win_streak"
                     + reachedAtSelect(h2, "streak_at") + reachedAtSelect(h2, "wins_at") + reachedAtSelect(h2, "best_at")
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
                    ps.addBatch();
                    if (++copied % 500 == 0)
                        ps.executeBatch();
                }
                ps.executeBatch();
                copyRewardCooldowns(h2, target);
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

    /** H2 files from older versions may lack a "reached at" column; read those as 0. */
    private static String reachedAtSelect(final Connection h2, final String column) throws SQLException {
        return hasColumn(h2, "arena_stats", column) ? ", " + column : ", 0 AS " + column;
    }

    private static void copyRewardCooldowns(final Connection h2, final Connection target) throws SQLException {
        // H2 files from before win rewards existed don't have this table
        try (ResultSet tables = h2.getMetaData().getTables(null, null, "ARENA_REWARDS", null)) {
            if (!tables.next())
                return;
        }

        final String upsert = "INSERT INTO arena_rewards (uuid, arena, last_reward) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE last_reward = GREATEST(last_reward, VALUES(last_reward))";
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery("SELECT uuid, arena, last_reward FROM arena_rewards");
             PreparedStatement ps = target.prepareStatement(upsert)) {
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setString(2, rs.getString("arena"));
                ps.setLong(3, rs.getLong("last_reward"));
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
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("SHUTDOWN");
            } catch (SQLException e) {
                Bukkit.getLogger().warning("[SpawnArena] H2 shutdown: " + e.getMessage());
            }
        }

        dataSource.close();
    }
}
