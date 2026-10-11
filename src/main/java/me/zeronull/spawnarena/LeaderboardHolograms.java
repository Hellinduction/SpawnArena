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

    public static String spawn(final String arena, final String stat, final String statLabel, final int limit, final Location loc) {
        final HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

        String name;
        do {
            name = NAME_PREFIX + arena.toLowerCase() + "_" + stat + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        } while (manager.getHologram(name).isPresent());

        final List<String> lines = new ArrayList<>();
        lines.add("<yellow>* <red><bold>" + displayName(arena) + " " + statLabel + "</bold></red> *</yellow>");
        for (int rank = 1; rank <= limit; rank++) {
            lines.add(String.format("<light_purple>%d. <yellow>%%spawnarena_top_player_%s_%s_%d%%<gray>: %%spawnarena_top_value_%s_%s_%d%%",
                    rank, arena, stat, rank, arena, stat, rank));
        }

        final TextHologramData data = new TextHologramData(name, loc);
        data.setText(lines);
        data.setTextUpdateInterval(UPDATE_INTERVAL_MS);
        data.setPersistent(true);

        final Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
        return name;
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
            if (h.getData() instanceof TextHologramData text && text.getTextUpdateInterval() != UPDATE_INTERVAL_MS)
                text.setTextUpdateInterval(UPDATE_INTERVAL_MS);
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
