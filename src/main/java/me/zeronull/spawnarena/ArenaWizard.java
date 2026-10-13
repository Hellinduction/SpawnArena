package me.zeronull.spawnarena;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /arenasetup: a step-by-step chat wizard with clickable buttons that pastes an arena, works out its
 * WorldGuard region, spawns and exit, sets its options and creates it. Every step has to be confirmed
 * before the next one, and the paste can be undone and redone as often as needed.
 *
 * <p>Buttons run hidden subcommands that carry the step they belong to, so clicking an old button from an
 * earlier message just shows the current step again instead of doing something unexpected.
 */
public final class ArenaWizard {
    private ArenaWizard() {}

    public static final String PERMISSION = "spawnarena.arenasetup";
    private static final long TIMEOUT_MILLIS = 20 * 60_000L;
    private static final int SCHEMATICS_PER_PAGE = 10;

    enum Type { NORMAL, SUMO, CRYSTAL }

    enum Step {
        TYPE("Arena type"), SCHEMATIC("Schematic"), PASTE("Paste"), REGION("Region"), SPAWNS("Spawns"),
        EXIT("Exit"), OPTIONS("Options"), SUMMARY("Summary");

        final String title;

        Step(final String title) {
            this.title = title;
        }
    }

    static final class Session {
        final UUID player;
        final String name;
        volatile Step step = Step.TYPE;
        Type type;
        int page;
        File schematic;          // null = arena already built, nothing to paste
        boolean schematicChosen;
        Location pasteAt;
        WorldEditUtils.Paste paste;
        boolean pasteConfirmed;
        // Region: a polygon (x/z points) between minY and maxY
        List<BlockVector2> points = new ArrayList<>();
        BlockVector2 centre;
        int minY, maxY;
        Location spawn1, spawn2, exit;
        boolean exitChosen;
        boolean crystalPvp, waterDeath, knockbackFish, plateWin, clearItems, multi, resetAfterFight = true;
        double winReward = 50000;
        volatile long touched = System.currentTimeMillis();
        volatile boolean busy;
        ScheduledTask preview;
        /** Setting up an existing arena again: the old one is replaced when they click Finish. */
        boolean replacing;
        /** What was where the paste went, on disk until the setup is finished or cancelled (see restoreLeftovers). */
        File backup;

        Session(final UUID player, final String name) {
            this.player = player;
            this.name = name;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Cancels setups nobody has touched for a while, undoing their paste. */
    public static void startTimeouts() {
        Threads.globalTimer(task -> {
            final long now = System.currentTimeMillis();
            for (final Session s : List.copyOf(SESSIONS.values())) {
                if (now - s.touched < TIMEOUT_MILLIS || s.busy)
                    continue;
                end(s);
                final Player p = Bukkit.getPlayer(s.player);
                if (p != null)
                    tell(p, "<gray>Your setup of <white>" + s.name + "</white> timed out and was cancelled"
                            + (s.paste != null ? " (the paste was undone)." : "."));
                if (s.paste != null)
                    WorldEditUtils.undo(s.pasteAt, s.paste).whenComplete((ok, error) -> {
                        if (error == null)
                            deleteBackup(s); // if the undo failed, the backup is put back on the next start
                    });
            }
        }, 1200L, 1200L);
    }

    // ── Backups of what a paste replaced ────────────────────────────────────

    private static File backupDir() {
        return new File(SpawnArena.INSTANCE.getDataFolder(), "wizard-backups");
    }

    /** Sidecar file saying where a backup goes back: "world x y z". */
    private static File whereFile(final File backup) {
        return new File(backup.getParentFile(), backup.getName().replace(".schem", ".where"));
    }

    private static void deleteBackup(final Session s) {
        if (s.backup == null)
            return;
        whereFile(s.backup).delete();
        s.backup.delete();
        s.backup = null;
    }

    /**
     * On startup: a setup that never finished (the server stopped or crashed) left its paste in the world.
     * Put back what was there before, so a half-made arena doesn't stay behind.
     */
    public static void restoreLeftovers() {
        final File[] wheres = backupDir().listFiles((d, n) -> n.endsWith(".where"));
        if (wheres == null)
            return;
        for (final File where : wheres) {
            final File backup = new File(where.getParentFile(), where.getName().replace(".where", ".schem"));
            try {
                final String[] parts = java.nio.file.Files.readString(where.toPath()).trim().split(" ");
                final World world = Bukkit.getWorld(parts[0]);
                if (world == null || !backup.exists()) {
                    where.delete();
                    backup.delete();
                    continue;
                }
                final Location at = new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                WorldEditUtils.paste(at, backup, false).whenComplete((ok, error) -> {
                    where.delete();
                    backup.delete();
                    SpawnArena.INSTANCE.getLogger().info("Undid the paste of an arena setup that was never finished, at "
                            + world.getName() + " " + parts[1] + " " + parts[2] + " " + parts[3] + ".");
                });
            } catch (final Exception e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't put back " + backup.getName() + ": " + e);
            }
        }
    }

    // ── Commands ────────────────────────────────────────────────────────────

    public static void handle(final Player player, final String[] args) {
        Session s = SESSIONS.get(player.getUniqueId());
        if (args.length == 0) {
            if (s == null)
                tell(player, "<gray>Start setting up a new arena with <gold>/arenasetup <name></gold>.");
            else
                show(player, s);
            return;
        }

        final String action = args[0].toLowerCase();
        // Deleting an arena works with or without a setup going on
        if (action.equals("delete")) {
            delete(player, args);
            return;
        }
        if (s == null) {
            if (action.equals("redo") && args.length >= 2) {
                start(player, args[1], true);
                return;
            }
            if (isAction(action)) {
                tell(player, "<gray>You're not setting up an arena. Start with <gold>/arenasetup <name></gold>.");
                return;
            }
            start(player, args[0], false);
            return;
        }
        if (!isAction(action)) {
            tell(player, "<red>You're already setting up <white>" + s.name + "</white>. "
                    + button("Continue", "/arenasetup show", "Show the current step", "green") + " "
                    + button("Cancel it", "/arenasetup cancel", "Stop this setup (undoes the paste)", "red"));
            return;
        }
        s.touched = System.currentTimeMillis();
        if (s.busy) {
            tell(player, "<gray>Hang on, still working on the last step...");
            return;
        }

        switch (action) {
            case "show" -> show(player, s);
            case "cancel" -> cancel(player, s);
            case "back" -> back(player, s, args);
            default -> {
                // Everything else belongs to a step; old buttons from earlier steps just re-show where we are
                final String stepArg = args.length >= 2 ? args[args.length - 1] : "";
                if (!stepArg.equals(String.valueOf(s.step.ordinal()))) {
                    show(player, s);
                    return;
                }
                act(player, s, action, args);
            }
        }
    }

    private static final List<String> ACTIONS = List.of("show", "cancel", "back", "redo", "delete", "type", "schem", "page", "paste", "undo",
            "ok", "bigger", "smaller", "taller", "shorter", "detect", "spawn1", "spawn2", "exit", "noexit", "toggle",
            "reward", "finish");

    private static boolean isAction(final String word) {
        return ACTIONS.contains(word);
    }

    public static List<String> tabComplete(final Player player, final String[] args) {
        // Starting: the name is theirs to type, so nothing to suggest
        if (args.length == 1 && !SESSIONS.containsKey(player.getUniqueId()))
            return List.of();
        if (args.length == 1)
            return List.of("show", "cancel");
        return List.of();
    }

    private static void start(final Player player, final String name, final boolean replacing) {
        if (!name.matches("[A-Za-z0-9_-]{2,32}")) {
            tell(player, "<red>Arena names can only use letters, numbers, _ and - (2-32 characters).");
            return;
        }
        final Arena existing = SpawnArena.arenas.of(name);
        if (existing != null && !replacing) {
            tell(player, "<yellow>There's already an arena called <white>" + existing.getArenaName() + "</white>. "
                    + button("Set it up again", "/arenasetup redo " + existing.getArenaName(),
                            "Go through the setup again; the old one is only replaced when you click Finish", "green") + " "
                    + button("Delete it", "/arenasetup delete " + existing.getArenaName(), "Remove this arena completely", "red"));
            return;
        }
        if (existing == null && replacing) {
            start(player, name, false);
            return;
        }
        if (isAction(name.toLowerCase())) {
            tell(player, "<red>Pick another name (that one's a wizard command).");
            return;
        }
        final Session s = new Session(player.getUniqueId(), existing != null ? existing.getArenaName() : name);
        s.replacing = replacing;
        SESSIONS.put(player.getUniqueId(), s);
        tell(player, "<light_purple><bold>Arena setup:</bold></light_purple> <white>" + name + "</white> <gray>— follow the steps; nothing is"
                + " created until you click Finish. " + button("Cancel", "/arenasetup cancel", "Stop (undoes the paste)", "red"));
        show(player, s);
    }

    private static void act(final Player player, final Session s, final String action, final String[] args) {
        switch (s.step) {
            case TYPE -> {
                if (!action.equals("type") || args.length < 3) { show(player, s); return; }
                s.type = switch (args[1].toLowerCase()) {
                    case "sumo" -> Type.SUMO;
                    case "crystal" -> Type.CRYSTAL;
                    default -> Type.NORMAL;
                };
                // Sensible defaults per type (changeable in Options)
                s.crystalPvp = s.type == Type.CRYSTAL;
                s.waterDeath = s.type == Type.SUMO;
                s.knockbackFish = s.type == Type.SUMO;
                // Crystal fights change the arena (and the cleanup is backed by a full re-paste); others don't need resets
                s.resetAfterFight = s.type == Type.CRYSTAL;
                goTo(player, s, Step.SCHEMATIC);
            }
            case SCHEMATIC -> {
                if (action.equals("page") && args.length >= 3) {
                    s.page = Math.max(0, parseInt(args[1], 0));
                    show(player, s);
                } else if (action.equals("schem") && args.length >= 3) {
                    if (args[1].equals("none")) {
                        s.schematic = null;
                        s.schematicChosen = true;
                        // keeps the type's default; with resets on, it's saved as a schematic at Finish
                        s.pasteAt = player.getLocation().getBlock().getLocation();
                        detectRegion(player, s, s.pasteAt, Step.REGION);
                        return;
                    }
                    final List<File> files = WorldEditUtils.listSchematics();
                    final int i = parseInt(args[1], -1);
                    if (i < 0 || i >= files.size()) { show(player, s); return; }
                    s.schematic = files.get(i);
                    s.schematicChosen = true;
                    goTo(player, s, Step.PASTE);
                } else {
                    show(player, s);
                }
            }
            case PASTE -> {
                switch (action) {
                    case "paste" -> paste(player, s);
                    case "undo" -> undoPaste(player, s, () -> show(player, s));
                    case "ok" -> {
                        if (s.paste == null) { show(player, s); return; }
                        s.pasteConfirmed = true;
                        detectRegion(player, s, s.pasteAt, Step.REGION);
                    }
                    default -> show(player, s);
                }
            }
            case REGION -> {
                switch (action) {
                    case "bigger" -> { grow(s, 1); show(player, s); }
                    case "smaller" -> { grow(s, -1); show(player, s); }
                    case "taller" -> { s.maxY++; show(player, s); }
                    case "shorter" -> { if (s.maxY - 1 > s.minY + 2) s.maxY--; show(player, s); }
                    case "detect" -> detectRegion(player, s, player.getLocation(), Step.REGION);
                    case "ok" -> {
                        if (s.type == Type.CRYSTAL && (s.spawn1 == null || s.spawn2 == null))
                            findFroglightSpawns(player, s);
                        else
                            goTo(player, s, Step.SPAWNS);
                    }
                    default -> show(player, s);
                }
            }
            case SPAWNS -> {
                switch (action) {
                    case "spawn1" -> { s.spawn1 = player.getLocation().clone(); faceEachOther(s); show(player, s); }
                    case "spawn2" -> { s.spawn2 = player.getLocation().clone(); faceEachOther(s); show(player, s); }
                    case "ok" -> {
                        if (s.spawn1 == null || s.spawn2 == null) { show(player, s); return; }
                        goTo(player, s, Step.EXIT);
                    }
                    default -> show(player, s);
                }
            }
            case EXIT -> {
                switch (action) {
                    case "exit" -> { s.exit = player.getLocation().clone(); s.exitChosen = true; show(player, s); }
                    case "noexit" -> { s.exit = null; s.exitChosen = true; goTo(player, s, Step.OPTIONS); }
                    case "ok" -> { if (s.exitChosen) goTo(player, s, Step.OPTIONS); else show(player, s); }
                    default -> show(player, s);
                }
            }
            case OPTIONS -> {
                switch (action) {
                    case "toggle" -> {
                        switch (args.length >= 3 ? args[1] : "") {
                            case "crystal" -> s.crystalPvp = !s.crystalPvp;
                            case "water" -> s.waterDeath = !s.waterDeath;
                            case "fish" -> s.knockbackFish = !s.knockbackFish;
                            case "plate" -> s.plateWin = !s.plateWin;
                            case "clear" -> s.clearItems = !s.clearItems;
                            case "multi" -> s.multi = !s.multi;
                            case "reset" -> s.resetAfterFight = !s.resetAfterFight;
                            default -> { }
                        }
                        show(player, s);
                    }
                    case "reward" -> {
                        final double change = args.length >= 3 && args[1].equals("down") ? -10000 : 10000;
                        s.winReward = Math.max(0, s.winReward + change);
                        show(player, s);
                    }
                    case "ok" -> goTo(player, s, Step.SUMMARY);
                    default -> show(player, s);
                }
            }
            case SUMMARY -> {
                if (action.equals("finish"))
                    finish(player, s);
                else
                    show(player, s);
            }
        }
    }

    private static void back(final Player player, final Session s, final String[] args) {
        final Step to;
        try {
            to = Step.valueOf(args.length >= 2 ? args[1].toUpperCase() : "");
        } catch (final IllegalArgumentException e) {
            show(player, s);
            return;
        }
        // Can't skip ahead past something not done yet, only go back
        if (to.ordinal() > s.step.ordinal() || (to == Step.PASTE && s.schematic == null)) {
            show(player, s);
            return;
        }
        if (to == Step.PASTE)
            s.pasteConfirmed = false;
        goTo(player, s, to);
    }

    private static void goTo(final Player player, final Session s, final Step step) {
        s.step = step;
        startPreview(player, s);
        show(player, s);
    }

    // ── Paste ───────────────────────────────────────────────────────────────

    private static void paste(final Player player, final Session s) {
        if (s.paste != null) {
            tell(player, "<gray>Undo the current paste first.");
            return;
        }
        s.busy = true;
        s.pasteAt = player.getLocation().getBlock().getLocation();
        // Where the backup goes back, written before pasting so even a crash mid-paste is covered
        s.backup = new File(backupDir(), s.name + "-" + s.player.toString().substring(0, 8) + ".schem");
        try {
            backupDir().mkdirs();
            java.nio.file.Files.writeString(whereFile(s.backup).toPath(), s.pasteAt.getWorld().getName() + " "
                    + s.pasteAt.getBlockX() + " " + s.pasteAt.getBlockY() + " " + s.pasteAt.getBlockZ());
        } catch (final java.io.IOException e) {
            s.busy = false;
            tell(player, "<red>Couldn't write the backup file, so nothing was pasted: " + escape(String.valueOf(e.getMessage())));
            return;
        }
        tell(player, "<gray>Pasting <white>" + s.schematic.getName() + "</white>...");
        WorldEditUtils.pasteUndoable(s.pasteAt, s.schematic, s.backup).whenComplete((paste, error) -> Threads.entity(player, () -> {
            s.busy = false;
            if (error != null) {
                deleteBackup(s);
                tell(player, "<red>Couldn't paste it: " + escape(String.valueOf(error.getMessage())));
                show(player, s);
                return;
            }
            s.paste = paste;
            show(player, s);
        }));
    }

    private static void undoPaste(final Player player, final Session s, final Runnable then) {
        if (s.paste == null) {
            then.run();
            return;
        }
        s.busy = true;
        WorldEditUtils.undo(s.pasteAt, s.paste).whenComplete((ok, error) -> Threads.entity(player, () -> {
            s.busy = false;
            if (error != null) {
                tell(player, "<red>Couldn't undo the paste: " + escape(String.valueOf(error.getMessage())));
                return;
            }
            s.paste = null;
            s.pasteConfirmed = false;
            deleteBackup(s);
            // Anything worked out from the old spot no longer applies
            s.points = new ArrayList<>();
            s.spawn1 = null;
            s.spawn2 = null;
            tell(player, "<yellow>Paste undone.");
            then.run();
        }));
    }

    // ── Region ──────────────────────────────────────────────────────────────

    private static final int[][] DIRECTIONS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * Works out the fighting area from {@code from} (the floor where you stand): eight rays go out until they
     * hit a wall, making an octagon, and the height goes up to the first ceiling. Sumo rings (no walls) get
     * the pasted box instead.
     */
    private static void detectRegion(final Player player, final Session s, final Location from, final Step then) {
        s.busy = true;
        final Location at = from.getBlock().getLocation();
        Threads.supplyAt(at, () -> {
            final World w = at.getWorld();
            final int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
            final BlockVector3 min = s.paste != null ? s.paste.min() : BlockVector3.at(cx - 40, cy - 10, cz - 40);
            final BlockVector3 max = s.paste != null ? s.paste.max() : BlockVector3.at(cx + 40, cy + 40, cz + 40);
            final List<BlockVector2> points = new ArrayList<>();

            if (s.type == Type.SUMO) {
                final int r = s.paste != null ? 0 : 12;
                final int x1 = s.paste != null ? min.x() : cx - r, x2 = s.paste != null ? max.x() : cx + r;
                final int z1 = s.paste != null ? min.z() : cz - r, z2 = s.paste != null ? max.z() : cz + r;
                points.add(BlockVector2.at(x1, z1));
                points.add(BlockVector2.at(x2, z1));
                points.add(BlockVector2.at(x2, z2));
                points.add(BlockVector2.at(x1, z2));
                s.minY = Math.min(cy - 3, s.paste != null ? min.y() : cy - 3);
                s.maxY = cy + 12;
            } else {
                for (final int[] d : DIRECTIONS) {
                    int steps = 0;
                    for (;;) {
                        final int nx = cx + d[0] * (steps + 1), nz = cz + d[1] * (steps + 1);
                        if (nx < min.x() || nx > max.x() || nz < min.z() || nz > max.z())
                            break;
                        // A wall is something at least 3 high: bumps in the floor (like the crystal pit's
                        // bedrock) and low fences don't stop the ray
                        if (!w.getBlockAt(nx, cy + 2, nz).isPassable())
                            break;
                        steps++;
                    }
                    points.add(BlockVector2.at(cx + d[0] * steps, cz + d[1] * steps));
                }
                // Up to the first ceiling (barriers count), or a dozen blocks
                int ceiling = Math.min(cy + 12, max.y());
                for (int y = cy + 2; y <= Math.min(cy + 64, max.y()); y++) {
                    final Block b = w.getBlockAt(cx, y, cz);
                    if (!b.isPassable() || b.getType() == Material.BARRIER) {
                        ceiling = y;
                        break;
                    }
                }
                s.minY = cy - 2;
                s.maxY = ceiling;
            }
            s.points = points;
            s.centre = BlockVector2.at(cx, cz);
            return null;
        }).whenComplete((ignored, error) -> Threads.entity(player, () -> {
            s.busy = false;
            if (error != null)
                tell(player, "<red>Couldn't work out the region: " + escape(String.valueOf(error.getMessage())));
            goTo(player, s, then);
        }));
    }

    /** Moves every corner one block further from (or closer to) the centre. */
    private static void grow(final Session s, final int by) {
        final List<BlockVector2> out = new ArrayList<>();
        for (final BlockVector2 p : s.points) {
            final int dx = Integer.signum(p.x() - s.centre.x()), dz = Integer.signum(p.z() - s.centre.z());
            final BlockVector2 moved = BlockVector2.at(p.x() + dx * by, p.z() + dz * by);
            // Don't shrink past the centre
            out.add(by < 0 && Integer.signum(moved.x() - s.centre.x()) != dx && dx != 0 ? p : moved);
        }
        s.points = out;
    }

    private static boolean inRegion(final Session s, final Location loc) {
        if (s.points.size() < 3 || loc.getBlockY() < s.minY || loc.getBlockY() > s.maxY)
            return false;
        // Point-in-polygon on block centres
        final double x = loc.getX(), z = loc.getZ();
        boolean inside = false;
        for (int i = 0, j = s.points.size() - 1; i < s.points.size(); j = i++) {
            final double xi = s.points.get(i).x() + 0.5, zi = s.points.get(i).z() + 0.5;
            final double xj = s.points.get(j).x() + 0.5, zj = s.points.get(j).z() + 0.5;
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi)
                inside = !inside;
        }
        return inside;
    }

    // ── Spawns ──────────────────────────────────────────────────────────────

    /** Crystal arenas mark their spawns with ochre froglights in the floor: use the two furthest apart. */
    private static void findFroglightSpawns(final Player player, final Session s) {
        s.busy = true;
        final World w = s.pasteAt.getWorld();
        Threads.supplyAt(s.pasteAt, () -> {
            final List<Block> found = new ArrayList<>();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (final BlockVector2 p : s.points) {
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++)
                    for (int y = s.minY - 2; y <= s.minY + 4; y++)
                        if (w.getBlockAt(x, y, z).getType() == Material.OCHRE_FROGLIGHT)
                            found.add(w.getBlockAt(x, y, z));
            Block a = null, b = null;
            double best = -1;
            for (int i = 0; i < found.size(); i++)
                for (int j = i + 1; j < found.size(); j++) {
                    final double d = found.get(i).getLocation().distanceSquared(found.get(j).getLocation());
                    if (d > best) { best = d; a = found.get(i); b = found.get(j); }
                }
            if (a != null) {
                s.spawn1 = a.getLocation().add(0.5, 1, 0.5);
                s.spawn2 = b.getLocation().add(0.5, 1, 0.5);
                faceEachOther(s);
            }
            return a != null;
        }).whenComplete((ok, error) -> Threads.entity(player, () -> {
            s.busy = false;
            if (Boolean.TRUE.equals(ok))
                tell(player, "<green>Found the two froglight spawn pads and used them.");
            goTo(player, s, Step.SPAWNS);
        }));
    }

    private static void faceEachOther(final Session s) {
        if (s.spawn1 == null || s.spawn2 == null)
            return;
        s.spawn1.setDirection(s.spawn2.toVector().subtract(s.spawn1.toVector()).setY(0));
        s.spawn2.setDirection(s.spawn1.toVector().subtract(s.spawn2.toVector()).setY(0));
        s.spawn1.setPitch(0);
        s.spawn2.setPitch(0);
    }

    // ── Finish / cancel ─────────────────────────────────────────────────────

    private static void finish(final Player player, final Session s) {
        final Arena existing = SpawnArena.arenas.of(s.name);
        if (existing != null && !s.replacing) {
            tell(player, "<red>An arena called <white>" + s.name + "</white> was created meanwhile. Cancel and pick another name.");
            return;
        }
        if (existing != null && !existing.getFights().isEmpty()) {
            tell(player, "<red>Can't replace it yet: a fight is going on there. Wait for it to end (or /arenalock on).");
            return;
        }

        // Already built + reset wanted: save the arena as it is now as a schematic, so it can be pasted back
        if (s.resetAfterFight && s.schematic == null) {
            File file = new File(WorldEditUtils.schematicsFolder(), s.name + ".schem");
            for (int i = 2; file.exists(); i++)
                file = new File(WorldEditUtils.schematicsFolder(), s.name + "_" + i + ".schem");
            final File target = file;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (final BlockVector2 p : s.points) {
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
            s.busy = true;
            tell(player, "<gray>Saving the arena as <white>" + target.getName() + "</white> so it can be reset after fights...");
            WorldEditUtils.saveSchematic(s.pasteAt.getWorld(), BlockVector3.at(minX, s.minY, minZ), BlockVector3.at(maxX, s.maxY, maxZ),
                    s.pasteAt, target).whenComplete((ok, error) -> Threads.entity(player, () -> {
                s.busy = false;
                if (error != null) {
                    tell(player, "<red>Couldn't save the schematic: " + escape(String.valueOf(error.getMessage()))
                            + " <gray>Turn off \"Reset after each fight\" to finish without it.");
                    return;
                }
                finishNow(player, s, target);
            }));
            return;
        }
        finishNow(player, s, s.resetAfterFight ? s.schematic : null);
    }

    /** Creates the region and the arena. {@code resetSchematic}: what to paste back after fights (null = no resets). */
    private static void finishNow(final Player player, final Session s, final File resetSchematic) {
        final Arena old = SpawnArena.arenas.of(s.name);
        if (old != null) {
            final String problem = removeArena(old, false);
            if (problem != null) {
                tell(player, "<red>Can't replace it yet: " + problem);
                return;
            }
        }
        final World world = s.spawn1.getWorld();
        try {
            final RegionManager regions = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(world));
            if (regions.hasRegion(s.name))
                regions.removeRegion(s.name);
            final ProtectedRegion region = new ProtectedPolygonalRegion(s.name, s.points, s.minY, s.maxY);
            region.setPriority(10);
            regions.addRegion(region);
            regions.save();
        } catch (final Exception e) {
            tell(player, "<red>Couldn't create the WorldGuard region: " + escape(String.valueOf(e.getMessage())));
            return;
        }

        final SavableArena arena = new SavableArena(s.name);
        SavableArena.createArenaConfig(arena.getConfigFile());
        final boolean reset = resetSchematic != null && s.pasteAt != null;
        arena.save(c -> {
            c.arenaName = s.name;
            c.crystalPvp = s.crystalPvp;
            c.deathOnTouchLiquid = s.waterDeath;
            c.giveKnockBackStick = s.knockbackFish;
            c.winOnStepStonePressurePlate = s.plateWin;
            c.shouldClearItems = s.clearItems;
            c.arenaMode = s.multi ? ArenaMode.MULTI.toString() : ArenaMode.SINGLE.toString();
            c.winReward = s.winReward;
            if (reset) {
                c.pasteLocation = s.pasteAt;
                c.schematic = resetSchematic.getPath();
            }
        });
        arena.setQueue(new ArenaQueue(arena));
        SpawnArena.arenas.add(arena);
        arena.setSpawnPoint1(s.spawn1);
        arena.setSpawnPoint2(s.spawn2);
        if (s.exit != null)
            arena.setExitLocation(s.exit);
        arena.reloadFromDisk();

        deleteBackup(s); // the paste stays: it's the arena now
        end(s);
        tell(player, "<green><bold>Arena " + s.name + " is ready!</bold></green> <gray>Players join with <gold>/joinarenaqueue " + s.name
                + "</gold> (or <gold>/joinarenaqueue " + s.name + " menu</gold>).");
    }

    // ── Deleting an arena ───────────────────────────────────────────────────

    /** /arenasetup delete <arena> [confirm|stats] */
    private static void delete(final Player player, final String[] args) {
        if (args.length < 2) {
            tell(player, "<red>/arenasetup delete <arena>");
            return;
        }
        final Arena arena = SpawnArena.arenas.of(args[1]);
        if (arena == null) {
            tell(player, "<red>There's no arena called <white>" + escape(args[1]) + "</white>.");
            return;
        }
        final String name = arena.getArenaName();
        if (args.length < 3) {
            tell(player, "<red><bold>Delete " + name + "?</bold></red> <gray>This removes the arena, its file, its WorldGuard region and its"
                    + " leaderboard holograms. Blocks in the world are left alone.\n"
                    + button("Delete it", "/arenasetup delete " + name + " confirm", "Delete the arena, keep its stats", "red") + " "
                    + button("Delete it and its stats", "/arenasetup delete " + name + " stats",
                            "Also wipe its wins, streaks, reward cooldowns and payout history", "dark_red") + " "
                    + button("Keep it", "/arenasetup show", "Don't delete anything", "green"));
            return;
        }
        final boolean wipeStats = args[2].equalsIgnoreCase("stats");
        final String problem = removeArena(arena, wipeStats);
        if (problem != null) {
            tell(player, "<red>Can't delete it: " + problem);
            return;
        }
        tell(player, "<green>Deleted arena <white>" + name + "</white>" + (wipeStats ? " and its stats." : ". Its stats were kept."));
    }

    /**
     * Removes an arena completely: from memory, its file, its WorldGuard region and its holograms (and its
     * stats if asked). Returns why it couldn't, or null when done.
     */
    static String removeArena(final Arena arena, final boolean wipeStats) {
        if (!arena.getFights().isEmpty())
            return "a fight is going on there. Wait for it to end (or /arenalock on).";
        final String name = arena.getArenaName();

        for (final Player p : arena.queue.clear())
            tell(p, "<red>Arena " + name + " was removed, so you were taken out of its queue.");
        SpawnArena.arenas.remove(arena);

        if (arena instanceof SavableArena savable) {
            final File file = savable.getConfigFile();
            if (file.exists() && !file.delete())
                SpawnArena.INSTANCE.getLogger().warning("Couldn't delete " + file);
        }

        for (final World world : Bukkit.getWorlds()) {
            final RegionManager regions = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(world));
            if (regions != null && regions.hasRegion(name)) {
                regions.removeRegion(name);
                try {
                    regions.save();
                } catch (final Exception e) {
                    SpawnArena.INSTANCE.getLogger().warning("Couldn't save WorldGuard regions after removing " + name + ": " + e);
                }
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
            LeaderboardHolograms.removeForArena(name);
        if (wipeStats)
            SpawnArena.statsManager.deleteArenaData(name);
        SpawnArena.INSTANCE.getLogger().info("Arena " + name + " was deleted" + (wipeStats ? " along with its stats." : "."));
        return null;
    }

    private static void cancel(final Player player, final Session s) {
        if (s.paste != null) {
            undoPaste(player, s, () -> {
                end(s);
                tell(player, "<yellow>Setup of <white>" + s.name + "</white> cancelled.");
            });
            return;
        }
        end(s);
        tell(player, "<yellow>Setup of <white>" + s.name + "</white> cancelled.");
    }

    private static void end(final Session s) {
        SESSIONS.remove(s.player);
        if (s.preview != null)
            s.preview.cancel();
    }

    // ── What each step shows ────────────────────────────────────────────────

    private static void show(final Player player, final Session s) {
        final StringBuilder m = new StringBuilder();
        final int n = s.step.ordinal();
        m.append("<dark_gray><strikethrough>                                        </strikethrough>\n")
                .append("<light_purple><bold>").append(s.name).append("</bold></light_purple> <gray>· step ").append(n + 1)
                .append("/").append(Step.values().length).append(": <white>").append(s.step.title).append("\n");
        final String tag = " " + n; // the step these buttons belong to

        switch (s.step) {
            case TYPE -> m.append("<gray>What kind of arena is it?\n")
                    .append(button("Normal", "/arenasetup type normal" + tag, "Regular PvP arena", "white")).append(" ")
                    .append(button("Sumo", "/arenasetup type sumo" + tag, "Knock them into the water (Slap Fish, water = out)", "aqua")).append(" ")
                    .append(button("Crystal PvP", "/arenasetup type crystal" + tag, "Players build and use end crystals; everything resets after", "light_purple"));
            case SCHEMATIC -> {
                final List<File> files = WorldEditUtils.listSchematics();
                m.append("<gray>Pick the schematic to paste (from your WorldEdit/FAWE schematics folder):\n");
                final int from = s.page * SCHEMATICS_PER_PAGE;
                for (int i = from; i < Math.min(files.size(), from + SCHEMATICS_PER_PAGE); i++)
                    m.append(" <dark_gray>•</dark_gray> ").append(button(escape(files.get(i).getName()), "/arenasetup schem " + i + tag,
                            "Paste " + escape(files.get(i).getName()), "aqua")).append("\n");
                if (files.isEmpty())
                    m.append(" <gray><italic>No schematics found.</italic>\n");
                if (s.page > 0)
                    m.append(button("« Prev", "/arenasetup page " + (s.page - 1) + tag, "Previous page", "gray")).append(" ");
                if (from + SCHEMATICS_PER_PAGE < files.size())
                    m.append(button("Next »", "/arenasetup page " + (s.page + 1) + tag, "Next page", "gray")).append(" ");
                m.append(button("It's already built", "/arenasetup schem none" + tag,
                        "No paste: stand in the middle of the fighting area, then click", "yellow"));
            }
            case PASTE -> {
                if (s.paste == null)
                    m.append("<gray>Stand where it should go. It's pasted like <white>//paste</white>: the schematic's origin lands at your feet.\n")
                            .append(button("Paste here", "/arenasetup paste" + tag, "Paste " + escape(s.schematic.getName()) + " where you're standing", "green"));
                else
                    m.append("<gray>Pasted at <white>").append(where(s.pasteAt)).append("</white>. Look around: is it in the right place?\n")
                            .append(button("✔ Looks good", "/arenasetup ok" + tag, "Keep it here", "green")).append(" ")
                            .append(button("↺ Undo & move", "/arenasetup undo" + tag, "Remove it again (puts back what was there), then paste somewhere else", "yellow"));
            }
            case REGION -> {
                final int w = span(s, true), d = span(s, false);
                m.append("<gray>The arena's region is shown with <red>red particles</red>: about <white>").append(w).append("×").append(d)
                        .append("</white>, from y <white>").append(s.minY).append("</white> to <white>").append(s.maxY).append("</white>.\n")
                        .append("<gray>It should cover the whole fighting area, and not the stands.\n")
                        .append(button("✔ Looks good", "/arenasetup ok" + tag, "Use this region", "green")).append(" ")
                        .append(button("+ Bigger", "/arenasetup bigger" + tag, "Push every side out a block", "white")).append(" ")
                        .append(button("− Smaller", "/arenasetup smaller" + tag, "Pull every side in a block", "white")).append(" ")
                        .append(button("↑ Taller", "/arenasetup taller" + tag, "Raise the top a block", "white")).append(" ")
                        .append(button("↓ Shorter", "/arenasetup shorter" + tag, "Lower the top a block", "white")).append("\n")
                        .append(button("⌖ Detect from where I'm standing", "/arenasetup detect" + tag,
                                "Stand on the fighting floor (in the middle is best) and work it out again from there", "aqua"));
            }
            case SPAWNS -> {
                m.append("<gray>Spawn 1 <green>(green)</green>: <white>").append(s.spawn1 == null ? "not set" : where(s.spawn1)).append(spawnWarning(s, s.spawn1))
                        .append("\n<gray>Spawn 2 <aqua>(blue)</aqua>: <white>").append(s.spawn2 == null ? "not set" : where(s.spawn2)).append(spawnWarning(s, s.spawn2))
                        .append("\n<gray>Stand where each team should start, then click. They're turned to face each other.\n")
                        .append(button("Set spawn 1 here", "/arenasetup spawn1" + tag, "Spawn 1 is where you're standing", "green")).append(" ")
                        .append(button("Set spawn 2 here", "/arenasetup spawn2" + tag, "Spawn 2 is where you're standing", "aqua"));
                if (s.spawn1 != null && s.spawn2 != null)
                    m.append(" ").append(button("✔ Looks good", "/arenasetup ok" + tag, "Use these spawns", "green"));
            }
            case EXIT -> {
                m.append("<gray>Where should people be sent if they're in the arena during a fight? <white>")
                        .append(s.exit == null ? (s.exitChosen ? "world spawn" : "not set") : where(s.exit)).append("\n")
                        .append(button("Set exit here", "/arenasetup exit" + tag, "Outside the arena, where you're standing", "yellow")).append(" ")
                        .append(button("Use the world spawn", "/arenasetup noexit" + tag, "Skip: they go to the world spawn", "gray"));
                if (s.exitChosen)
                    m.append(" ").append(button("✔ Looks good", "/arenasetup ok" + tag, "Use this exit", "green"));
            }
            case OPTIONS -> m.append("<gray>Click to switch an option:\n")
                    .append(toggle("Crystal PvP", s.crystalPvp, "crystal", tag, "Players can build and use end crystals")).append("  ")
                    .append(toggle("Water = out", s.waterDeath, "water", tag, "Touching water loses (sumo)")).append("  ")
                    .append(toggle("Slap Fish", s.knockbackFish, "fish", tag, "Give everyone a knockback fish")).append("\n")
                    .append(toggle("Pressure plate wins", s.plateWin, "plate", tag, "Stepping on a stone pressure plate wins")).append("  ")
                    .append(toggle("Clear inventory", s.clearItems, "clear", tag, "Fight with an empty inventory (it's given back after)")).append("  ")
                    .append(toggle("Several fights at once", s.multi, "multi", tag, "MULTI mode: more than one fight at a time")).append("\n")
                    .append(s.schematic == null
                            ? toggle("Reset after each fight", s.resetAfterFight, "reset", tag,
                                    "Saves the arena as it is now as a schematic at Finish, and pastes it back after every fight")
                            : toggle("Reset after each fight", s.resetAfterFight, "reset", tag, "Paste the schematic again after every fight"))
                    .append("\n<gray>Win reward: <white>").append(StatsManager.formatMoney(s.winReward)).append("</white> ")
                    .append(button("−10k", "/arenasetup reward down" + tag, "Lower the win reward", "red")).append(" ")
                    .append(button("+10k", "/arenasetup reward up" + tag, "Raise the win reward", "green")).append("\n")
                    .append(button("✔ Done", "/arenasetup ok" + tag, "On to the summary", "green"));
            case SUMMARY -> {
                m.append("<gray>Type: <white>").append(s.type).append("</white>  Schematic: <white>")
                        .append(s.schematic == null ? "none" : escape(s.schematic.getName())).append("</white>\n")
                        .append("<gray>Region: <white>").append(span(s, true)).append("×").append(span(s, false)).append(", y ")
                        .append(s.minY).append("–").append(s.maxY).append("</white>  Spawns: <white>").append(where(s.spawn1)).append(" / ")
                        .append(where(s.spawn2)).append("</white>\n<gray>Exit: <white>").append(s.exit == null ? "world spawn" : where(s.exit))
                        .append("</white>  Reward: <white>").append(StatsManager.formatMoney(s.winReward)).append("</white>  Reset after fights: <white>")
                        .append(!s.resetAfterFight ? "no" : s.schematic == null ? "yes (saves it as " + s.name + ".schem)" : "yes")
                        .append("</white>\n")
                        .append(button("✔ Finish", "/arenasetup finish" + tag, "Create the region and the arena", "green"));
            }
        }

        // Going back to any earlier step
        if (n > 0) {
            m.append("\n<dark_gray>Back to:</dark_gray>");
            for (final Step st : Step.values()) {
                if (st.ordinal() >= n)
                    break;
                if (st == Step.PASTE && s.schematic == null)
                    continue;
                m.append(" ").append(button(st.title, "/arenasetup back " + st.name().toLowerCase(), "Go back to " + st.title, "dark_aqua"));
            }
        }
        m.append("  ").append(button("✖ Cancel", "/arenasetup cancel", "Stop the setup (undoes the paste)", "red"));
        tell(player, m.toString());
    }

    private static String spawnWarning(final Session s, final Location spawn) {
        return spawn != null && !inRegion(s, spawn) ? " <red>(outside the region!)</red>" : "";
    }

    private static int span(final Session s, final boolean x) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (final BlockVector2 p : s.points) {
            lo = Math.min(lo, x ? p.x() : p.z());
            hi = Math.max(hi, x ? p.x() : p.z());
        }
        return s.points.isEmpty() ? 0 : hi - lo + 1;
    }

    // ── Particle preview (only the person setting up sees it) ──────────────

    private static void startPreview(final Player player, final Session s) {
        if (s.preview != null)
            s.preview.cancel();
        s.preview = player.getScheduler().runAtFixedRate(SpawnArena.INSTANCE, task -> {
            if (!SESSIONS.containsKey(s.player)) {
                task.cancel();
                return;
            }
            drawPreview(player, s);
        }, null, 1L, 20L);
    }

    private static void drawPreview(final Player player, final Session s) {
        final World w = player.getWorld();
        if (s.step.ordinal() >= Step.REGION.ordinal() && s.points.size() >= 3) {
            final Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.2f);
            final double bottom = s.minY + 2.2, top = s.maxY + 1;
            for (int i = 0; i < s.points.size(); i++) {
                final BlockVector2 a = s.points.get(i), b = s.points.get((i + 1) % s.points.size());
                line(player, w, a.x() + 0.5, bottom, a.z() + 0.5, b.x() + 0.5, bottom, b.z() + 0.5, red);
                line(player, w, a.x() + 0.5, top, a.z() + 0.5, b.x() + 0.5, top, b.z() + 0.5, red);
                line(player, w, a.x() + 0.5, bottom, a.z() + 0.5, a.x() + 0.5, top, a.z() + 0.5, red);
            }
        }
        pillar(player, s.spawn1, Color.LIME);
        pillar(player, s.spawn2, Color.AQUA);
        pillar(player, s.exit, Color.YELLOW);
    }

    private static void line(final Player p, final World w, final double x1, final double y1, final double z1,
                             final double x2, final double y2, final double z2, final Particle.DustOptions dust) {
        final double len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        final int steps = Math.max(1, (int) (len / 0.75));
        for (int i = 0; i <= steps; i++) {
            final double t = i / (double) steps;
            p.spawnParticle(Particle.DUST, new Location(w, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t), 1, 0, 0, 0, 0, dust);
        }
    }

    private static void pillar(final Player p, final Location at, final Color colour) {
        if (at == null || !at.getWorld().equals(p.getWorld()))
            return;
        final Particle.DustOptions dust = new Particle.DustOptions(colour, 1.5f);
        for (double y = 0; y <= 3; y += 0.25)
            p.spawnParticle(Particle.DUST, at.clone().add(0, y, 0), 1, 0, 0, 0, 0, dust);
    }

    // ── Chat helpers ────────────────────────────────────────────────────────

    private static void tell(final Player player, final String miniMessage) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private static String button(final String label, final String command, final String hover, final String colour) {
        return "<click:run_command:'" + command + "'><hover:show_text:'<gray>" + hover.replace("'", "") + "'><" + colour + ">[" + label + "]</"
                + colour + "></hover></click>";
    }

    private static String toggle(final String label, final boolean on, final String key, final String tag, final String hover) {
        return button((on ? "✔ " : "✖ ") + label, "/arenasetup toggle " + key + tag, hover + " (click to switch)", on ? "green" : "gray");
    }

    private static String where(final Location l) {
        return l == null ? "not set" : l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private static String escape(final String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    private static int parseInt(final String s, final int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}
