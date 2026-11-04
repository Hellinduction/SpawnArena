package me.zeronull.spawnarena;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Only touch this class after checking FancyHolograms is enabled — it links against its API. */
public final class LeaderboardHolograms {
    /** How far away the boards show (FancyHolograms' own default is only 20 blocks). */
    private static final int VISIBILITY_DISTANCE = 64;
    public static final String NAME_PREFIX = "spawnarena_lb_";
    private static final int UPDATE_INTERVAL_MS = 5_000;

    // Text displays render upward from their location; ~0.25 blocks per line at scale 1
    private static final double LINE_HEIGHT = 0.25;
    private static final double PADDING = 0.1;
    private static final int MAX_SCAN = 16;
    /** How far above the floor it goes when there's room, so the bottom line is clear of the ground. */
    public static final double LIFT = 0.5;

    private LeaderboardHolograms() {}

    public record Placement(Location location, boolean fits) {}

    /** Nudges the hologram out of the floor and a bit above it, then down from any ceiling its top would clip into. */
    public static Placement correctPlacement(final Location requested, final int lineCount) {
        final Location loc = requested.clone();
        final double height = lineCount * LINE_HEIGHT + PADDING;

        // Raise out of the ground (handles slabs/stairs via the collision box top)
        for (int i = 0; i < MAX_SCAN; i++) {
            final org.bukkit.block.Block block = loc.getBlock();
            if (block.isPassable()) break;
            final double top = block.getBoundingBox().getMaxY();
            loc.setY(Math.max(top, block.getY() + 1e-3) + PADDING);
        }
        final double floor = loc.getY();
        loc.setY(floor + LIFT);

        // Find the lowest solid block the hologram's column would intersect
        Double ceiling = null;
        final int startY = (int) Math.floor(floor);
        final int endY = (int) Math.floor(loc.getY() + height);
        for (int y = startY; y <= endY; y++) {
            final org.bukkit.block.Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.isPassable()) continue;
            final double bottom = block.getBoundingBox().getMinY();
            if (bottom > floor) {
                ceiling = bottom;
                break;
            }
        }

        if (ceiling == null || loc.getY() + height <= ceiling)
            return new Placement(loc, true);

        // Lower it so the top sits just under the ceiling, but never back into the floor
        final double lowered = ceiling - height - PADDING;
        if (lowered >= floor - 1e-6) {
            loc.setY(lowered);
            return new Placement(loc, true);
        }
        loc.setY(floor);
        return new Placement(loc, false);
    }

    public static String spawn(final String arena, final String stat, final int limit, final Location loc) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

        String name;
        do {
            name = NAME_PREFIX + arena.toLowerCase() + "_" + stat + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        } while (manager.getHologram(name).isPresent());

        final TextHologramData data = new TextHologramData(name, loc);
        data.setText(leaderboardLines(arena, stat, limit));
        data.setTextUpdateInterval(intervalFor(stat));
        data.setTextShadow(true);
        data.setPersistent(true);
        data.setVisibilityDistance(VISIBILITY_DISTANCE);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        rememberCreated(name);
        return name;
    }

    private static List<String> leaderboardLines(final String arena, final String stat, final int limit) {
        final List<String> lines = new ArrayList<>();
        lines.add("<yellow>* <red><bold>" + displayName(arena) + " " + statLabel(stat) + "</bold></red> *</yellow>");
        for (int rank = 1; rank <= limit; rank++) {
            lines.add(String.format("<light_purple>%d. <yellow>%%spawnarena_top_player_%s_%s_%d%%<gray>: %%spawnarena_top_value_%s_%s_%d%%",
                    rank, arena, stat, rank, arena, stat, rank));
        }
        // Each viewer sees their own place here
        lines.add(SEPARATOR);
        lines.add(String.format("<gray>You: <light_purple>%%spawnarena_my_rank_%s_%s%% <dark_gray>· <yellow>%%spawnarena_my_value_%s_%s%%",
                arena, stat, arena, stat));
        return lines;
    }

    private static final String SEPARATOR = "<dark_gray><strikethrough>                              </strikethrough>";
    /** Lines a leaderboard has besides its rows: the title, the separator and the viewer's own line. */
    public static final int EXTRA_LINES = 3;

    private static String statLabel(final String stat) {
        return BoardStats.label(stat);
    }

    /** How often a board redraws: every second for the live free-for-all kill streak, otherwise every 5. */
    private static int intervalFor(final String stat) {
        return BoardStats.isLive(stat) ? LIVE_UPDATE_INTERVAL_MS : UPDATE_INTERVAL_MS;
    }

    private static final int LIVE_UPDATE_INTERVAL_MS = 1_000;

    static String displayName(final String arena) {
        return Arena.displayName(arena);
    }

    private static final String REWARD_TAG = "_reward_";
    private static final int REWARD_UPDATE_INTERVAL_MS = 1_000;
    public static final int REWARD_LINES = 8;

    /** A per-viewer live countdown to that viewer's next win reward in this arena. */
    public static String spawnReward(final String arena, final Location loc) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

        String name;
        do {
            name = NAME_PREFIX + arena.toLowerCase() + REWARD_TAG + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        } while (manager.getHologram(name).isPresent());

        final TextHologramData data = new TextHologramData(name, loc);
        data.setText(rewardLines(arena));
        data.setTextUpdateInterval(REWARD_UPDATE_INTERVAL_MS);
        data.setTextShadow(true);
        data.setPersistent(true);
        data.setVisibilityDistance(VISIBILITY_DISTANCE);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        rememberCreated(name);
        return name;
    }

    private static List<String> rewardLines(final String arena) {
        return new ArrayList<>(List.of(
                "<gradient:#FFD700:#FF8C00><bold>✦ " + displayName(arena) + " Rewards ✦</bold></gradient>",
                "<gray>Win: <#55FF55><bold>%spawnarena_reward_amount_" + arena + "%</bold></#55FF55> <dark_gray>·</dark_gray> <gray>once every <aqua>%spawnarena_reward_every_" + arena + "%</aqua>",
                "<gold>★ <yellow>#1 streak: <#55FF55><bold>%spawnarena_reward_leaderamount_" + arena
                        + "%</bold></#55FF55> <yellow>per win <dark_gray>·</dark_gray> <yellow>once every <aqua>%spawnarena_reward_leaderevery_" + arena + "%</aqua> <gold>★",
                "<gray>#2 streak: <#55FF55>%spawnarena_reward_secondamount_" + arena + "%</#55FF55> <gray>per win <dark_gray>/</dark_gray> <aqua>%spawnarena_reward_secondevery_" + arena
                        + "%</aqua> <dark_gray>·</dark_gray> <gray>#3: <#55FF55>%spawnarena_reward_thirdamount_" + arena + "%</#55FF55> <gray>per win <dark_gray>/</dark_gray> <aqua>%spawnarena_reward_thirdevery_" + arena + "%</aqua>",
                "<dark_gray><strikethrough>                              </strikethrough>",
                "<gray>Next reward: <#FFE066><bold>%spawnarena_reward_status_" + arena + "%</bold></#FFE066>",
                "<gray>Last win: <white>%spawnarena_reward_last_" + arena + "%</white>",
                "<gray>Today: <white>%spawnarena_reward_today%</white> <dark_gray>/</dark_gray> <white>%spawnarena_reward_dailycap%</white><gray>%spawnarena_reward_dailyreset%",
                "<dark_gray>Alts don't pay · max %spawnarena_reward_samelimit% paid wins in a row vs one player"
        ));
    }

    private static final String GLOBAL = "global";
    private static final String STATS_TAG = "_stats";
    public static final int STATS_LINES = 8;

    /**
     * Totals for one arena ({@code arena}), or every arena together (null): matches, players, time fought, free-for-all
     * kills (where there's a free-for-all), rewards paid, and the viewer's own matches.
     */
    public static String spawnStats(final String arena, final Location loc) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

        String name;
        do {
            name = NAME_PREFIX + (arena == null ? GLOBAL : arena.toLowerCase() + STATS_TAG) + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        } while (manager.getHologram(name).isPresent());

        final TextHologramData data = new TextHologramData(name, loc);
        data.setText(statsLines(arena));
        data.setTextUpdateInterval(UPDATE_INTERVAL_MS);
        data.setTextShadow(true);
        data.setPersistent(true);
        data.setVisibilityDistance(VISIBILITY_DISTANCE);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        rememberCreated(name);
        return name;
    }

    private static List<String> statsLines(final String arena) {
        final String of = arena == null ? "" : "_" + arena;
        final boolean ffa = me.zeronull.spawnarena.SpawnArena.arenas != null && (arena == null
                ? java.util.stream.StreamSupport.stream(me.zeronull.spawnarena.SpawnArena.arenas.spliterator(), false).anyMatch(Arena::isFfa)
                : me.zeronull.spawnarena.SpawnArena.arenas.of(arena) != null && me.zeronull.spawnarena.SpawnArena.arenas.of(arena).isFfa());
        final List<String> lines = new ArrayList<>();
        lines.add("<gradient:#FFD700:#FF8C00><bold>✦ " + (arena == null ? "Global Spawn Arena" : displayName(arena)) + " Stats ✦</bold></gradient>");
        if (arena != null && ffa) {
            // A free-for-all has no matches or win rewards: its kills instead
            lines.add("<gray>Players who've fought: <yellow>%spawnarena_global_players" + of + "%");
            lines.add("<gray>Time spent fighting: <yellow>%spawnarena_global_time" + of + "%");
            lines.add("<gray>Free-for-all kills: <yellow>%spawnarena_global_ffa_kills" + of + "%");
            lines.add(SEPARATOR);
            lines.add("<gray>Your kills: <light_purple>%spawnarena_ffa_kills" + of + "%");
            return lines;
        }
        lines.add("<gray>Matches played: <yellow>%spawnarena_global_matches" + of + "%");
        lines.add("<gray>Players who've fought: <yellow>%spawnarena_global_players" + of + "%");
        lines.add("<gray>Time spent fighting: <yellow>%spawnarena_global_time" + of + "%");
        if (ffa)
            lines.add("<gray>Free-for-all kills: <yellow>%spawnarena_global_ffa_kills" + of + "%");
        lines.add("<gray>Win rewards paid: <#55FF55>%spawnarena_global_paid" + of + "%");
        lines.add(SEPARATOR);
        lines.add("<gray>Your matches: <light_purple>%spawnarena_global_my_matches" + of + "%");
        return lines;
    }

    // ── "Ready!" poof ───────────────────────────────────────────────────────

    private static final double READY_EFFECT_RADIUS = 16;
    /** "uuid:hologram" -> whether that player's reward was ready at the last check */
    private static final java.util.Map<String, Boolean> WAS_READY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Once a second: a poof at a reward hologram, for a nearby player, the moment their reward becomes ready. */
    public static void startReadyEffects() {
        me.zeronull.spawnarena.Threads.globalTimer(task -> tickReadyEffects(), 20L, 20L);
    }

    private static void tickReadyEffects() {
        final me.zeronull.spawnarena.StatsManager stats = me.zeronull.spawnarena.SpawnArena.statsManager;
        if (stats == null)
            return;

        // Players who logged off near a hologram are never "far away", so drop them here
        WAS_READY.keySet().removeIf(key -> org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(key.substring(0, key.indexOf(':')))) == null);

        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            final String name = h.getData().getName();
            if (!name.startsWith(NAME_PREFIX))
                continue;
            final String rest = boardPart(name);
            if (!rest.endsWith("_reward"))
                continue;

            final String arenaName = resolveArena(rest.substring(0, rest.length() - "_reward".length()));
            final Arena arena = arenaName == null ? null : me.zeronull.spawnarena.SpawnArena.arenas.of(arenaName);
            final Location loc = h.getData().getLocation();
            if (arena == null || arena.getWinReward() <= 0 || loc == null || loc.getWorld() == null)
                continue;

            // Each player is checked on their own thread (Folia)
            for (final org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!loc.getWorld().equals(p.getWorld()))
                    continue;
                me.zeronull.spawnarena.Threads.entity(p, () -> checkReady(p, name, loc, arena, stats));
            }
        }
    }

    private static void checkReady(final org.bukkit.entity.Player p, final String name, final Location loc, final Arena arena,
                                   final me.zeronull.spawnarena.StatsManager stats) {
        final String key = p.getUniqueId() + ":" + name;
        if (!loc.getWorld().equals(p.getWorld()) || p.getLocation().distanceSquared(loc) > READY_EFFECT_RADIUS * READY_EFFECT_RADIUS) {
            WAS_READY.remove(key); // coming back later starts fresh, without a poof
            return;
        }

        final boolean ready = !stats.hasReachedDailyCap(p.getUniqueId())
                && stats.rewardCooldownRemaining(p.getUniqueId(), arena) <= 0;
        final Boolean wasReady = WAS_READY.put(key, ready);
        if (wasReady != null && !wasReady && ready)
            p.spawnParticle(org.bukkit.Particle.POOF,
                    loc.clone().add(0, REWARD_LINES * LINE_HEIGHT / 2, 0), 30, 0.6, 0.6, 0.6, 0.02);
    }

    public record UpdateResult(int updated, List<String> skipped) {}

    /**
     * Rebuilds every SpawnArena hologram's text with the current layout, keeping its name and position.
     * The arena, stat and row count are read back from the hologram's name and existing text.
     */
    public static UpdateResult updateAll() {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        int updated = 0;
        final List<String> skipped = new ArrayList<>();

        for (final Hologram h : manager.getHolograms()) {
            final String name = h.getData().getName();
            if (!name.startsWith(NAME_PREFIX) || !(h.getData() instanceof TextHologramData text))
                continue;

            // spawnarena_lb_<arena>_<stat|reward>_<4 digits>
            final String rest = boardPart(name);
            final List<String> lines;
            final int interval;

            if (rest.equals(GLOBAL)) {
                lines = statsLines(null);
                interval = UPDATE_INTERVAL_MS;
            } else if (rest.endsWith(STATS_TAG)) {
                final String arena = resolveArena(rest.substring(0, rest.length() - STATS_TAG.length()));
                if (arena == null) { skipped.add(name); continue; }
                lines = statsLines(arena);
                interval = UPDATE_INTERVAL_MS;
            } else if (rest.endsWith("_reward")) {
                final String arena = resolveArena(rest.substring(0, rest.length() - "_reward".length()));
                if (arena == null) { skipped.add(name); continue; }
                lines = rewardLines(arena);
                interval = REWARD_UPDATE_INTERVAL_MS;
            } else {
                final String[] board = boardOf(name);
                if (board == null) { skipped.add(name); continue; }
                final String arena = board[0], stat = board[1];
                final int rows = Math.max(1, Math.min((int) text.getText().stream().filter(l -> l.contains("spawnarena_top_player_")).count(),
                        me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT));
                lines = leaderboardLines(arena, stat, rows);
                interval = intervalFor(stat);
            }

            text.setText(lines);
            text.setTextUpdateInterval(interval);
            text.setTextShadow(true);
            if (text.getVisibilityDistance() < VISIBILITY_DISTANCE)
                text.setVisibilityDistance(VISIBILITY_DISTANCE);
            h.forceUpdate();
            h.refreshForViewers();
            updated++;
        }

        manager.saveHolograms();
        return new UpdateResult(updated, skipped);
    }

    private static final java.util.regex.Pattern BOARD_ID = java.util.regex.Pattern.compile("_\\d{4}(?=_|$)");

    /**
     * The &lt;arena&gt;_&lt;stat|reward&gt; part of spawnarena_lb_&lt;arena&gt;_&lt;stat|reward&gt;_&lt;4 digits&gt;, also for a copy that got
     * something added after the digits (e.g. _bunnyspawn), so copies keep being updated like the original.
     */
    private static String boardPart(final String name) {
        final String rest = name.substring(NAME_PREFIX.length());
        final java.util.regex.Matcher id = BOARD_ID.matcher(rest);
        while (id.find()) {
            final String part = rest.substring(0, id.start());
            if (part.endsWith("_reward") || part.endsWith(STATS_TAG) || part.equals(GLOBAL) || BoardStats.split(part, null) != null)
                return part;
        }
        return rest.replaceFirst("_\\d{4}$", "");
    }

    /** A leaderboard hologram's {arena, stat}, read back from its name; null for reward holograms and unknown ones. */
    private static String[] boardOf(final String name) {
        if (!name.startsWith(NAME_PREFIX))
            return null;
        // spawnarena_lb_<arena>_<stat>_<4 digits>
        final String rest = boardPart(name);
        final String[] board = BoardStats.split(rest);
        final String arena = board == null ? null : resolveArena(board[0]);
        return arena == null ? null : new String[]{arena, board[1]};
    }

    /** Hologram names store the arena in lower case; map it back to the arena's real name. */
    private static String resolveArena(final String lowerName) {
        if (me.zeronull.spawnarena.SpawnArena.arenas == null)
            return null;
        for (final me.zeronull.spawnarena.Arena a : me.zeronull.spawnarena.SpawnArena.arenas)
            if (a.getArenaName().equalsIgnoreCase(lowerName))
                return a.getArenaName();
        return null;
    }

    public static boolean remove(final String name) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        return manager.getHologram(name).map(h -> {
            manager.removeHologram(h);
            return true;
        }).orElse(false);
    }

    /**
     * After a result in this arena: its leaderboards show it now rather than at their next redraw (every
     * UPDATE_INTERVAL_MS). Call off the main thread: the boards' new rows and everyone's places are read here first, so
     * the redraw itself (on the global thread) only reads them from the cache. Reward and stats holograms, which read
     * per viewer, keep their own timing.
     */
    public static void refreshArena(final String arena) {
        final me.zeronull.spawnarena.StatsManager stats = me.zeronull.spawnarena.SpawnArena.statsManager;
        if (stats == null)
            return;
        final List<Hologram> boards = new ArrayList<>();
        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            final String[] board = boardOf(h.getData().getName());
            if (board == null || !board[0].equalsIgnoreCase(arena))
                continue;
            final String field = BoardStats.field(board[1]);
            if (field == null)
                continue; // the live one reads the free-for-all itself, and redraws every second anyway
            stats.getTopPlayersCached(board[0], field, me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT);
            stats.getStanding(new java.util.UUID(0, 0), board[0], field); // everyone's place on it, for the "You" lines
            boards.add(h);
        }
        if (!boards.isEmpty())
            me.zeronull.spawnarena.Threads.global(() -> boards.forEach(Hologram::refreshForViewers));
    }

    /**
     * Into another world (a MULTI crystal copy and back, say): a moment later, every hologram near them there is shown
     * to them afresh. Their game drops what it was showing when they change world, and FancyHolograms could still
     * count them as seeing a hologram, so never send it again: leaderboards right in front of them stayed invisible.
     */
    public static final class WorldChange implements org.bukkit.event.Listener {
        /** A teleport this far (or into another world) gets the holograms around them shown afresh too. */
        private static final double FAR = 64;

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onChangeWorld(final org.bukkit.event.player.PlayerChangedWorldEvent e) {
            reshowLater(e.getPlayer());
        }

        /**
         * Teleporting far and back within a second or so (FancyHolograms checks who's in range once a second): their game
         * dropped the holograms with the area, but FancyHolograms never noticed they'd gone, so never sent them again.
         */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent e) {
            final Location from = e.getFrom(), to = e.getTo();
            if (to != null && from.getWorld() != null && from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) > FAR * FAR)
                reshowLater(e.getPlayer());
        }

        private static void reshowLater(final org.bukkit.entity.Player player) {
            me.zeronull.spawnarena.Threads.entityLater(player, () -> {
                if (!player.isOnline() || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
                    return;
                final org.bukkit.World world = player.getWorld();
                for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
                    final Location at = h.getData().getLocation();
                    if (at == null || !world.equals(at.getWorld()))
                        continue;
                    if (h.isViewer(player))
                        h.forceHideHologram(player);
                    h.forceUpdateShownStateFor(player);
                }
            }, 30L);
        }
    }

    /** Redraws every leaderboard hologram now (and brings older ones up to the current refresh interval and distance). */
    public static int refreshAll() {
        int count = 0;
        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            if (!h.getData().getName().startsWith(NAME_PREFIX))
                continue;
            final String[] board = boardOf(h.getData().getName());
            final int interval = h.getData().getName().contains(REWARD_TAG) ? REWARD_UPDATE_INTERVAL_MS
                    : board != null ? intervalFor(board[1]) : UPDATE_INTERVAL_MS;
            if (h.getData() instanceof TextHologramData text && text.getTextUpdateInterval() != interval)
                text.setTextUpdateInterval(interval);
            if (h.getData().getVisibilityDistance() < VISIBILITY_DISTANCE)
                h.getData().setVisibilityDistance(VISIBILITY_DISTANCE);
            h.refreshForViewers();
            count++;
        }
        return count;
    }

    /** Removes every leaderboard/reward hologram belonging to this arena. Returns how many. */
    public static int removeForArena(final String arena) {
        int removed = 0;
        for (final String name : list()) {
            // spawnarena_lb_<arena>_<stat|reward|stats>_<4 digits>
            final String rest = boardPart(name);
            final String[] board = BoardStats.split(rest, a -> a.equalsIgnoreCase(arena));
            final boolean ours = (board != null && board[0].equalsIgnoreCase(arena))
                    || rest.equalsIgnoreCase(arena + "_reward") || rest.equalsIgnoreCase(arena + STATS_TAG);
            if (ours && remove(name))
                removed++;
        }
        return removed;
    }

    /** Where a SpawnArena hologram is, or null if there's none by that name. */
    public static Location locationOf(final String name) {
        if (!name.startsWith(NAME_PREFIX))
            return null;
        return FancyHologramsPlugin.get().getHologramManager().getHologram(name)
                .map(h -> h.getData().getLocation().clone()).orElse(null);
    }


    /**
     * The middle of a hologram's text (its location is the bottom of the text, which grows upwards), for facing it.
     * Null if there's no hologram by that name.
     */
    public static Location middleOf(final String name) {
        if (!name.startsWith(NAME_PREFIX))
            return null;
        return FancyHologramsPlugin.get().getHologramManager().getHologram(name).map(h -> {
            final Location at = h.getData().getLocation().clone();
            if (h.getData() instanceof TextHologramData text) {
                final double scale = text.getScale() == null ? 1 : text.getScale().y();
                final double translation = text.getTranslation() == null ? 0 : text.getTranslation().y();
                at.add(0, translation + text.getText().size() * LINE_HEIGHT * scale / 2, 0);
            }
            return at;
        }).orElse(null);
    }

    /**
     * Clear ground to look at a hologram from: a few blocks away on whichever side is open, in line with it and facing
     * the middle of its text. Null if there's nowhere to stand nearby (it floats over nothing). Call on the thread that
     * owns the hologram's chunk.
     */
    public static Location viewingSpot(final Location base, final Location middle) {
        final org.bukkit.World world = middle.getWorld();
        final double[][] sides = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}, {0.7071, 0.7071}, {0.7071, -0.7071}, {-0.7071, 0.7071}, {-0.7071, -0.7071}};
        for (final int distance : new int[]{3, 4, 2, 5, 6}) {
            for (final double[] side : sides) {
                // Exactly that far from the hologram itself, not from the corner of its block
                final double x = middle.getX() + side[0] * distance;
                final double z = middle.getZ() + side[1] * distance;
                for (final int dy : new int[]{0, -1, 1, -2, 2, -3, -4, 3}) {
                    final int y = base.getBlockY() + dy;
                    final org.bukkit.block.Block feet = world.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
                    if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()
                            && feet.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid())
                        return facing(new Location(world, x, y, z), middle);
                }
            }
        }
        return null;
    }

    /** In the air 3 blocks from it (for someone who can fly, when there's no ground near it). */
    public static Location airSpot(final Location base, final Location middle) {
        return facing(base.clone().add(0, 0, 3), middle);
    }

    /** Turned to look straight at the middle of the hologram's text. */
    private static Location facing(final Location spot, final Location middle) {
        spot.setDirection(middle.toVector().subtract(spot.clone().add(0, 1.62, 0).toVector()));
        return spot;
    }

    /** Every leaderboard hologram, oldest first (ones made before the order was kept come first, by name). */
    public static List<String> list() {
        final List<String> names = new ArrayList<>();
        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            final String n = h.getData().getName();
            if (n.startsWith(NAME_PREFIX))
                names.add(n);
        }
        final List<String> order = createdOrder();
        // Unknown ones (indexOf -1) predate the kept order, so they're the oldest and come first
        names.sort(java.util.Comparator.<String>comparingInt(order::indexOf).thenComparing(java.util.Comparator.naturalOrder()));
        return names;
    }

    // FancyHolograms doesn't keep the order holograms were made in, so it's kept here, one name per line
    private static java.io.File orderFile() {
        return new java.io.File(SpawnArena.INSTANCE.getDataFolder(), "leaderboard-order.txt");
    }

    private static synchronized List<String> createdOrder() {
        try {
            return orderFile().exists() ? java.nio.file.Files.readAllLines(orderFile().toPath()) : new ArrayList<>();
        } catch (java.io.IOException e) {
            return new ArrayList<>();
        }
    }

    /** Adds a new hologram to the end, dropping names of ones that no longer exist. */
    private static synchronized void rememberCreated(final String name) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        final List<String> order = new ArrayList<>(createdOrder());
        order.removeIf(n -> n.equals(name) || manager.getHologram(n).isEmpty());
        order.add(name);
        try {
            java.nio.file.Files.write(orderFile().toPath(), order);
        } catch (java.io.IOException e) {
            org.bukkit.Bukkit.getLogger().warning("[SpawnArena] Couldn't save the leaderboard order: " + e.getMessage());
        }
    }
}
