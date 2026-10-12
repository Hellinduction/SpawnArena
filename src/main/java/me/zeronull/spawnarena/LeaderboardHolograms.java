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
    public static final String NAME_PREFIX = "spawnarena_lb_";
    private static final int UPDATE_INTERVAL_MS = 5_000;

    // Text displays render upward from their location; ~0.25 blocks per line at scale 1
    private static final double LINE_HEIGHT = 0.25;
    private static final double PADDING = 0.1;
    private static final int MAX_SCAN = 16;

    private LeaderboardHolograms() {}

    public record Placement(Location location, boolean fits) {}

    /** Nudges the hologram out of the floor, then down from any ceiling its top would clip into. */
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

        // Find the lowest solid block the hologram's column would intersect
        Double ceiling = null;
        final int startY = loc.getBlockY();
        final int endY = (int) Math.floor(loc.getY() + height);
        for (int y = startY; y <= endY; y++) {
            final org.bukkit.block.Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.isPassable()) continue;
            final double bottom = block.getBoundingBox().getMinY();
            if (bottom > loc.getY()) {
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
        data.setTextUpdateInterval(UPDATE_INTERVAL_MS);
        data.setPersistent(true);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        return name;
    }

    private static List<String> leaderboardLines(final String arena, final String stat, final int limit) {
        final List<String> lines = new ArrayList<>();
        lines.add("<yellow>* <red><bold>" + displayName(arena) + " " + statLabel(stat) + "</bold></red> *</yellow>");
        for (int rank = 1; rank <= limit; rank++) {
            lines.add(String.format("<light_purple>%d. <yellow>%%spawnarena_top_player_%s_%s_%d%%<gray>: %%spawnarena_top_value_%s_%s_%d%%",
                    rank, arena, stat, rank, arena, stat, rank));
        }
        return lines;
    }

    private static String statLabel(final String stat) {
        return switch (stat) {
            case "streak" -> "Win Streak";
            case "best_streak" -> "Best Win Streak";
            default -> "Wins";
        };
    }

    /** All-lowercase names are title cased ("sumo_pit" → "Sumo Pit"); any other casing is kept as typed. */
    static String displayName(final String arena) {
        if (!arena.equals(arena.toLowerCase()))
            return arena;

        final StringBuilder out = new StringBuilder();
        for (final String word : arena.split("[_\\-\\s]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? arena : out.toString();
    }

    private static final String REWARD_TAG = "_reward_";
    private static final int REWARD_UPDATE_INTERVAL_MS = 1_000;
    public static final int REWARD_LINES = 10;

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
        data.setPersistent(true);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        return name;
    }

    private static List<String> rewardLines(final String arena) {
        return new ArrayList<>(List.of(
                "<gradient:#FFD700:#FF8C00><bold>✦ " + displayName(arena) + " Rewards ✦</bold></gradient>",
                "<gray>Win a fight to earn <#55FF55><bold>%spawnarena_reward_amount_" + arena + "%</bold></#55FF55>",
                "<dark_gray><strikethrough>                              </strikethrough>",
                "<gray>Your next reward",
                "<#FFE066><bold>%spawnarena_reward_status_" + arena + "%</bold></#FFE066>",
                "<gray>Last win: <white>%spawnarena_reward_last_" + arena + "%</white>",
                "<gray>Earned today: <white>%spawnarena_reward_today%</white> <dark_gray>/</dark_gray> <white>%spawnarena_reward_dailycap%</white>",
                "<white>Claimable once every <aqua>%spawnarena_reward_every_" + arena + "%</aqua>",
                "<gold>★ <yellow>#1 win streak earns <#55FF55><bold>%spawnarena_reward_leaderamount_" + arena
                        + "%</bold></#55FF55> <yellow>every <aqua>%spawnarena_reward_leaderevery_" + arena + "%</aqua> <gold>★",
                "<dark_gray>Alts don't count · max %spawnarena_reward_samelimit% payouts in a row vs the same player"
        ));
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
            final String rest = name.substring(NAME_PREFIX.length()).replaceFirst("_\\d{4}$", "");
            final List<String> lines;
            final int interval;

            if (rest.endsWith("_reward")) {
                final String arena = resolveArena(rest.substring(0, rest.length() - "_reward".length()));
                if (arena == null) { skipped.add(name); continue; }
                lines = rewardLines(arena);
                interval = REWARD_UPDATE_INTERVAL_MS;
            } else {
                String stat = null;
                for (final String s : new String[]{"best_streak", "streak", "wins"}) {
                    if (rest.endsWith("_" + s)) { stat = s; break; }
                }
                final String arena = stat == null ? null : resolveArena(rest.substring(0, rest.length() - stat.length() - 1));
                if (arena == null) { skipped.add(name); continue; }
                final int rows = Math.max(1, Math.min(text.getText().size() - 1,
                        me.zeronull.spawnarena.commands.impl.ArenaLeaderboardCommand.MAX_LIMIT));
                lines = leaderboardLines(arena, stat, rows);
                interval = UPDATE_INTERVAL_MS;
            }

            text.setText(lines);
            text.setTextUpdateInterval(interval);
            h.forceUpdate();
            h.refreshForViewers();
            updated++;
        }

        manager.saveHolograms();
        return new UpdateResult(updated, skipped);
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

    /** Redraws every leaderboard hologram now (and brings older ones up to the current refresh interval). */
    public static int refreshAll() {
        int count = 0;
        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            if (!h.getData().getName().startsWith(NAME_PREFIX))
                continue;
            final int interval = h.getData().getName().contains(REWARD_TAG) ? REWARD_UPDATE_INTERVAL_MS : UPDATE_INTERVAL_MS;
            if (h.getData() instanceof TextHologramData text && text.getTextUpdateInterval() != interval)
                text.setTextUpdateInterval(interval);
            h.refreshForViewers();
            count++;
        }
        return count;
    }

    public static List<String> list() {
        final List<String> names = new ArrayList<>();
        for (final Hologram h : FancyHologramsPlugin.get().getHologramManager().getHolograms()) {
            final String n = h.getData().getName();
            if (n.startsWith(NAME_PREFIX))
                names.add(n);
        }
        return names;
    }
}
