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
 * /arenamove &lt;arena&gt; here | &lt;world&gt; [dx dy dz]: points an arena at another spot, e.g. after the spawn moved to a new
 * world. Its spawns, exit and paste location move by the offset, and its WorldGuard regions (the arena's and
 * &lt;arena&gt;_copy) are copied there, flags and all. Stats stay, as they go by the arena's name. The build itself
 * isn't moved: it must already be there (or paste it there).
 */
public final class ArenaMoveCommand extends ArenaTabComplete implements CommandExecutor {
    public ArenaMoveCommand() {
        super(0);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length != 2 && args.length != 5) {
            sender.sendMessage(ChatColor.RED + "Usage: /arenamove <arena> here  (standing on its spawn 1's block in the new spot)");
            sender.sendMessage(ChatColor.RED + "   or: /arenamove <arena> <world> [dx dy dz]  (same coordinates, or moved by that much)");
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

        final World to;
        final int dx, dy, dz;
        if (args[1].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only a player can use \"here\". From the console: /arenamove <arena> <world> [dx dy dz]");
                return true;
            }
            final Location at = player.getLocation();
            to = at.getWorld();
            dx = at.getBlockX() - config.spawnPoint1.getBlockX();
            dy = at.getBlockY() - config.spawnPoint1.getBlockY();
            dz = at.getBlockZ() - config.spawnPoint1.getBlockZ();
        } else {
            to = Bukkit.getWorld(args[1]);
            if (to == null) {
                sender.sendMessage(ChatColor.RED + "No world called " + args[1] + " is loaded.");
                return true;
            }
            try {
                dx = args.length == 5 ? Integer.parseInt(args[2]) : 0;
                dy = args.length == 5 ? Integer.parseInt(args[3]) : 0;
                dz = args.length == 5 ? Integer.parseInt(args[4]) : 0;
            } catch (final NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "dx, dy and dz must be whole numbers.");
                return true;
            }
        }
        if (to.equals(from) && dx == 0 && dy == 0 && dz == 0) {
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
                moveRegions(arena.getArenaName(), from, to, BlockVector3.at(dx, dy, dz), regionNotes);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "/arenamove couldn't copy " + arena.getArenaName() + "'s regions", e);
                sender.sendMessage(ChatColor.RED + "Couldn't copy the WorldGuard regions, so nothing was moved: " + e.getMessage());
                return true;
            }
        }

        final List<Player> queued = arena.queue.clear();
        for (final Player p : queued)
            p.sendMessage(ChatColor.YELLOW + "You were taken out of the " + arena.getArenaName() + " queue as the arena is being moved.");
        arena.save(c -> {
            c.spawnPoint1 = shifted(c.spawnPoint1, to, dx, dy, dz);
            c.spawnPoint2 = shifted(c.spawnPoint2, to, dx, dy, dz);
            c.exitLocation = shifted(c.exitLocation, to, dx, dy, dz);
            c.pasteLocation = shifted(c.pasteLocation, to, dx, dy, dz);
        });
        arena.reloadFromDisk();
        // A MULTI crystal arena's copies are copied from the arena again, at its new spot
        if (arena.isCrystalPvp() && arena.getArenaMode() == ArenaMode.MULTI && CrystalCopies.supported()) {
            CrystalCopies.retire(arena);
            CrystalCopies.setUp(arena, true);
        }

        sender.sendMessage(ChatColor.GREEN + "Moved " + arena.getArenaName() + " to " + to.getName()
                + (dx == 0 && dy == 0 && dz == 0 ? " (same coordinates)" : " (moved by " + dx + " " + dy + " " + dz + ")") + ".");
        for (final String note : regionNotes)
            sender.sendMessage(ChatColor.GRAY + note);
        if (!Arena.isSpawnWorld(to))
            sender.sendMessage(ChatColor.GOLD + to.getName() + " isn't in settings.yml's spawnWorlds yet: add it and /arenareload, or nobody can queue there.");
        if (!to.equals(from))
            sender.sendMessage(ChatColor.GRAY + "Its regions in " + from.getName() + " were left as they were. Leaderboard holograms and NPCs aren't moved.");
        return true;
    }

    private static Location shifted(final Location loc, final World to, final int dx, final int dy, final int dz) {
        if (loc == null)
            return null;
        final Location moved = loc.clone().add(dx, dy, dz);
        moved.setWorld(to);
        return moved;
    }

    /** Copies the arena's region and its _copy region (if any) into {@code to}, moved by {@code by}, flags and all. */
    private static void moveRegions(final String arena, final World from, final World to, final BlockVector3 by, final List<String> notes) {
        final RegionManager source = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(from));
        final RegionManager target = Arena.ArenaUtils.REGION_CONTAINER.get(BukkitAdapter.adapt(to));
        if (source == null || target == null)
            throw new IllegalStateException("WorldGuard has no regions for " + (source == null ? from : to).getName());
        for (final String id : new String[]{arena, arena + "_copy"}) {
            final ProtectedRegion old = source.getRegion(id);
            if (old == null) {
                if (id.equals(arena))
                    notes.add("There's no region " + id + " in " + from.getName() + " to copy: make one around the arena in " + to.getName() + ".");
                continue;
            }
            final ProtectedRegion moved;
            if (old instanceof ProtectedPolygonalRegion) {
                final List<BlockVector2> points = old.getPoints().stream().map(p -> p.add(by.x(), by.z())).toList();
                moved = new ProtectedPolygonalRegion(id, points, old.getMinimumPoint().y() + by.y(), old.getMaximumPoint().y() + by.y());
            } else if (old instanceof ProtectedCuboidRegion) {
                moved = new ProtectedCuboidRegion(id, old.getMinimumPoint().add(by), old.getMaximumPoint().add(by));
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
                notes.add("Replaced the region " + id + " that was already in " + to.getName() + ".");
            target.addRegion(moved);
            notes.add("Copied region " + id + " (flags, priority and members) into " + to.getName() + ".");
        }
        try {
            target.saveChanges();
        } catch (final Exception e) {
            notes.add("WorldGuard will save the regions itself shortly (" + e.getMessage() + ").");
        }
    }
}
