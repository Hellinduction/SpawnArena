package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.WorldEditUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.logging.Level;

/**
 * /arenapaste <schematic> <world> <x> <y> <z> [noair] — pastes a schematic (like //paste, its origin at x y z), from the
 * console too, which WorldEdit's own commands can't always be run from. What was there is saved first to
 * plugins/SpawnArena/paste-backups/, so "/arenapaste paste-backups/<file> <world> <x> <y> <z>" puts it all back.
 * A schematic name is looked up in WorldEdit's/FAWE's schematics folder; a path with a / is under plugins/SpawnArena.
 */
public final class ArenaPasteCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: /arenapaste <schematic> <world> <x> <y> <z> [noair]");
            return true;
        }
        final File schematic = args[0].contains("/")
                ? new File(SpawnArena.INSTANCE.getDataFolder(), args[0].endsWith(".schem") ? args[0] : args[0] + ".schem")
                : WorldEditUtils.getSchematic(args[0].replaceFirst("\\.schem$", ""));
        if (!schematic.isFile()) {
            sender.sendMessage("§cNo schematic " + schematic.getPath());
            return true;
        }
        final World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("§cNo world " + args[1]);
            return true;
        }
        final int x, y, z;
        try {
            x = Integer.parseInt(args[2]);
            y = Integer.parseInt(args[3]);
            z = Integer.parseInt(args[4]);
        } catch (final NumberFormatException e) {
            sender.sendMessage("§cx, y and z must be whole numbers.");
            return true;
        }
        final boolean noAir = args.length >= 6 && args[5].equalsIgnoreCase("noair");
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        final File backup = new File(SpawnArena.INSTANCE.getDataFolder(),
                "paste-backups/" + schematic.getName().replaceFirst("\\.schem$", "") + "-" + stamp + ".schem");
        sender.sendMessage("§7Pasting " + schematic.getName() + " at " + x + " " + y + " " + z + (noAir ? " (its air left out)" : "") + "...");
        WorldEditUtils.pasteWithBackup(new Location(world, x, y, z), schematic, backup, noAir).whenComplete((box, error) -> {
            if (error != null) {
                SpawnArena.INSTANCE.getLogger().log(Level.SEVERE, "/arenapaste " + String.join(" ", args) + " failed", error);
                sender.sendMessage("§cPaste failed (nothing was changed if the backup couldn't be saved): " + error.getMessage());
                return;
            }
            final String msg = "Pasted " + schematic.getName() + " into " + box[0] + " to " + box[1] + ". Undo: /arenapaste paste-backups/"
                    + backup.getName() + " " + world.getName() + " " + x + " " + y + " " + z;
            SpawnArena.INSTANCE.getLogger().info(msg);
            sender.sendMessage("§a" + msg);
        });
        return true;
    }
}
