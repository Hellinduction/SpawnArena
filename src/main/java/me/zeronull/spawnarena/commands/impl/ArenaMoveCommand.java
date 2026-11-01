package me.zeronull.spawnarena.commands.impl;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaMode;
import me.zeronull.spawnarena.CrystalCopies;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import me.zeronull.spawnarena.config.impl.ArenaConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * /arenamove &lt;arena&gt; here | &lt;world&gt; [dx dy dz] [turn]: points an arena at another spot, e.g. after the spawn moved
 * to a new world, also when its build was pasted there turned. Its spawns, exit and paste location move (and turn, about
 * spawn 1), the schematic is pasted turned from then on, and its WorldGuard regions (the arena's and &lt;arena&gt;_copy) are
 * copied there, flags and all. Stats stay, as they go by the arena's name. The build itself isn't moved: it must already
 * be there (or paste it there).
 * <p>
 * With "here", the player stands on spawn 1's block in the new spot looking toward spawn 2; the turn is how far that
 * is from the old spawn 1 to spawn 2 direction, to the nearest quarter.
 */
public final class ArenaMoveCommand extends ArenaTabComplete implements CommandExecutor {
    public ArenaMoveCommand() {
        super(0);
    }

    private static void usage(final CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Usage: /arenamove <arena> here  (on spawn 1's block in the new spot, looking toward spawn 2)");
        sender.sendMessage(ChatColor.RED + "   or: /arenamove <arena> <world> [dx dy dz] [0|90|180|270]  (moved by that much, turned clockwise about spawn 1)");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 2 || args.length == 4 || args.length > 6) {
            usage(sender);
            return true;
        }
        final Arena found = SpawnArena.arenas.of(args[0]);
        if (!(found instanceof SavableArena arena)) {
            sender.sendMessage(ChatColor.RED + "That arena does not exist!");
            return true;
        }
        final ArenaConfig config = SavableArena.getArenaConfig(arena.getConfigFile());
        if (config.spawnPoint1 == null || config.spawnPoint1.getWorld() == null) {
            sender.sendMessage(ChatColor.RED + "That arena has no spawn 1 (or its world isn't loaded), so there's nothing to move it from.");
            return true;
        }
        final World from = config.spawnPoint1.getWorld();
        final BlockVector3 pivot = BlockVector3.at(config.spawnPoint1.getBlockX(), config.spawnPoint1.getBlockY(), config.spawnPoint1.getBlockZ());

        final World to;
        final BlockVector3 at;
        final int turn;
        if (args[1].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player) || args.length != 2) {
                usage(sender);
                return true;
            }
            if (config.spawnPoint2 == null) {
                sender.sendMessage(ChatColor.RED + "That arena has no spawn 2, so which way it's turned can't be told. Use /arenamove <arena> <world> dx dy dz <turn>.");
                return true;
            }
            final Location here = player.getLocation();
            to = here.getWorld();
            at = BlockVector3.at(here.getBlockX(), here.getBlockY(), here.getBlockZ());
            final double before = yawOf(config.spawnPoint2.getX() - config.spawnPoint1.getX(), config.spawnPoint2.getZ() - config.spawnPoint1.getZ());
            turn = Math.floorMod(Math.round((here.getYaw() - before) / 90f) * 90, 360);
        } else {
            to = Bukkit.getWorld(args[1]);
            if (to == null) {
                sender.sendMessage(ChatColor.RED + "No world called " + args[1] + " is loaded.");
                return true;
            }
            try {
                at = args.length >= 5 ? pivot.add(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])) : pivot;
                turn = args.length == 6 || args.length == 3 ? Integer.parseInt(args[args.length - 1]) : 0;
            } catch (final NumberFormatException e) {
                usage(sender);
                return true;
            }
            if (turn % 90 != 0 || turn < 0 || turn >= 360) {
                sender.sendMessage(ChatColor.RED + "The turn must be 0, 90, 180 or 270 (clockwise, seen from above).");
                return true;
            }
        }
        final Move move = new Move(to, pivot, at, turn);
        if (to.equals(from) && at.equals(pivot) && turn == 0) {
            sender.sendMessage(ChatColor.RED + "That's where it already is.");
            return true;
        }
        if (!arena.allFights().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "There's a match going on in that arena. Lock it (/arenalock " + arena.getArenaName()
                    + ") and move it once it's over.");
            return true;
        }

        // Regions first: if WorldGuard can't take them, nothing has changed yet
        final List<String> regionNotes = new ArrayList<>();
        if (Arena.ArenaUtils.WORLD_EDIT_SUPPORT) {
            try {
                moveRegions(arena.getArenaName(), from, move, regionNotes);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "/arenamove couldn't copy " + arena.getArenaName() + "'s regions", e);
                sender.sendMessage(ChatColor.RED + "Couldn't copy the WorldGuard regions, so nothing was moved: " + e.getMessage());
                return true;
            }
        }

        for (final Player p : arena.queue.clear())
            p.sendMessage(ChatColor.YELLOW + "You were taken out of the " + arena.getArenaName() + " queue as the arena is being moved.");
        arena.save(c -> {
            c.spawnPoint1 = move.location(c.spawnPoint1);
            c.spawnPoint2 = move.location(c.spawnPoint2);
            c.exitLocation = move.location(c.exitLocation);
            if (c.pasteLocation != null) {
                // The schematic is pasted about its own origin, which is the paste location's block
                final BlockVector3 paste = move.block(BlockVector3.at(c.pasteLocation.getBlockX(), c.pasteLocation.getBlockY(), c.pasteLocation.getBlockZ()));
                c.pasteLocation = new Location(to, paste.x(), paste.y(), paste.z());
            }
            c.pasteTurn = Math.floorMod(c.pasteTurn + turn, 360);
        });
        arena.reloadFromDisk();
        // A MULTI crystal arena's copies are copied from the arena again, at its new spot
        if (arena.isCrystalPvp() && arena.getArenaMode() == ArenaMode.MULTI && CrystalCopies.supported()) {
            CrystalCopies.retire(arena);
            CrystalCopies.setUp(arena, true);
        }

        final BlockVector3 by = at.subtract(pivot);
        sender.sendMessage(ChatColor.GREEN + "Moved " + arena.getArenaName() + " to " + to.getName() + " (moved by " + by.x() + " " + by.y() + " " + by.z()
                + (turn == 0 ? ", not turned" : ", turned " + turn + "° clockwise") + ").");
        final ArenaConfig now = SavableArena.getArenaConfig(arena.getConfigFile());
        if (now.spawnPoint2 != null)
            sender.sendMessage(ChatColor.GRAY + "Spawn 2 is now at " + now.spawnPoint2.getBlockX() + " " + now.spawnPoint2.getBlockY() + " "
                    + now.spawnPoint2.getBlockZ() + ": check it's on the other spawn.");
        for (final String note : regionNotes)
            sender.sendMessage(ChatColor.GRAY + note);
        if (!Arena.isSpawnWorld(to))
            sender.sendMessage(ChatColor.GOLD + to.getName() + " isn't in settings.yml's spawnWorlds yet: add it and /arenareload, or nobody can queue there.");
        if (!to.equals(from))
            sender.sendMessage(ChatColor.GRAY + "Its regions in " + from.getName() + " were left as they were. Leaderboard holograms and NPCs aren't moved.");
        return true;
    }

    /** Minecraft's yaw for looking along (dx, dz): 0 = south (+z), 90 = west, 180 = north, 270 = east. */
    private static double yawOf(final double dx, final double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Moves spawn 1's block {@code from} onto {@code at} in {@code to}, turning everything about it clockwise by {@code turn}. */
    private record Move(World to, BlockVector3 from, BlockVector3 at, int turn) {
        /** (x, z) turned clockwise as seen from above (east goes south): the same way as WorldEdit's //rotate. */
        private double[] turned(final double x, final double z) {
            return switch (this.turn) {
                case 90 -> new double[]{-z, x};
                case 180 -> new double[]{-x, -z};
                case 270 -> new double[]{z, -x};
                default -> new double[]{x, z};
            };
        }

        BlockVector3 block(final BlockVector3 b) {
            final double[] t = this.turned(b.x() - this.from.x(), b.z() - this.from.z());
            return BlockVector3.at(this.at.x() + (int) t[0], this.at.y() + b.y() - this.from.y(), this.at.z() + (int) t[1]);
        }

        /** A spot keeps its place in its block (about the block's centre) and turns its facing with the arena. */
        Location location(final Location loc) {
            if (loc == null)
                return null;
            final double[] t = this.turned(loc.getX() - this.from.x() - 0.5, loc.getZ() - this.from.z() - 0.5);
            return new Location(this.to, this.at.x() + 0.5 + t[0], loc.getY() - this.from.y() + this.at.y(), this.at.z() + 0.5 + t[1],
                    loc.getYaw() + this.turn, loc.getPitch());
        }
    }

    /** Copies the arena's region and its _copy region (if any) into the new spot, moved and turned, flags and all. */
    private static void moveRegions(final String arena, final World from, final Move move, final List<String> notes) {
        final RegionManager source = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(from));
        final RegionManager target = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(move.to()));
        if (source == null || target == null)
            throw new IllegalStateException("WorldGuard has no regions for " + (source == null ? from : move.to()).getName());
        for (final String id : new String[]{arena, arena + "_copy"}) {
            final ProtectedRegion old = source.getRegion(id);
            if (old == null) {
                if (id.equals(arena))
                    notes.add("There's no region " + id + " in " + from.getName() + " to copy: make one around the arena in " + move.to().getName() + ".");
                continue;
            }
            final ProtectedRegion moved;
            if (old instanceof ProtectedPolygonalRegion) {
                final List<BlockVector2> points = old.getPoints().stream()
                        .map(p -> move.block(BlockVector3.at(p.x(), move.from().y(), p.z())).toBlockVector2()).toList();
                final int dy = move.at().y() - move.from().y();
                moved = new ProtectedPolygonalRegion(id, points, old.getMinimumPoint().y() + dy, old.getMaximumPoint().y() + dy);
            } else if (old instanceof ProtectedCuboidRegion) {
                final BlockVector3 a = move.block(old.getMinimumPoint());
                final BlockVector3 b = move.block(old.getMaximumPoint());
                moved = new ProtectedCuboidRegion(id, a.getMinimum(b), a.getMaximum(b));
            } else {
                notes.add("Region " + id + " isn't a box or polygon, so it wasn't copied.");
                continue;
            }
            moved.setPriority(old.getPriority());
            moved.setFlags(old.getFlags());
            moved.setOwners(new DefaultDomain(old.getOwners()));
            moved.setMembers(new DefaultDomain(old.getMembers()));
            final ProtectedRegion parent = old.getParent() == null ? null : target.getRegion(old.getParent().getId());
            if (parent != null) {
                try {
                    moved.setParent(parent);
                } catch (final ProtectedRegion.CircularInheritanceException ignored) {
                    // left without a parent
                }
            }
            if (target.hasRegion(id))
                notes.add("Replaced the region " + id + " that was already in " + move.to().getName() + ".");
            target.addRegion(moved);
            notes.add("Copied region " + id + " (flags, priority and members) into " + move.to().getName() + ".");
        }
        try {
            target.saveChanges();
        } catch (final Exception e) {
            notes.add("WorldGuard will save the regions itself shortly (" + e.getMessage() + ").");
        }
    }
}
