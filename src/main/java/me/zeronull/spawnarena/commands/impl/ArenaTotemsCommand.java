package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /arenatotems &lt;arena&gt; [limit] — how many Totems of Undying can save each fighter per fight there (0 = no limit).
 * Without a number, says what it is. Applies to fights already going on too.
 */
public final class ArenaTotemsCommand extends ArenaTabComplete implements CommandExecutor {
    public ArenaTotemsCommand() {
        super(0);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /arenatotems <arena> [limit] (0 = no limit)");
            return true;
        }
        final Arena found = SpawnArena.arenas.of(args[0]);
        if (!(found instanceof SavableArena arena)) {
            sender.sendMessage(ChatColor.RED + "That arena does not exist!");
            return true;
        }
        if (args.length < 2) {
            final int limit = arena.getTotemLimit();
            sender.sendMessage(ChatColor.GRAY + arena.getArenaName() + ": " + (limit <= 0 ? ChatColor.WHITE + "no totem limit"
                    : ChatColor.WHITE + String.valueOf(limit) + ChatColor.GRAY + " totem" + (limit == 1 ? "" : "s") + " per fighter per fight")
                    + ChatColor.GRAY + ".");
            return true;
        }
        final int limit;
        try {
            limit = Integer.parseInt(args[1]);
        } catch (final NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "'" + args[1] + "' isn't a number. Use 0 for no limit.");
            return true;
        }
        if (limit < 0) {
            sender.sendMessage(ChatColor.RED + "Use 0 for no limit, or a number above it.");
            return true;
        }
        arena.setTotemLimit(limit);
        arena.save(config -> config.totemLimit = limit);
        me.zeronull.spawnarena.CrystalCopies.sync(arena); // a MULTI crystal arena's copies follow
        sender.sendMessage(limit == 0
                ? ChatColor.GREEN + "No totem limit in " + arena.getArenaName() + " any more."
                : ChatColor.GREEN + "In " + arena.getArenaName() + ", each fighter can now be saved by " + limit + " totem"
                        + (limit == 1 ? "" : "s") + " per fight" + (arena.isFfa() ? " (each time they come in)" : "")
                        + ". Fights going on now follow it too.");
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 2)
            return java.util.stream.Stream.of("0", "1", "2", "3").filter(s -> s.startsWith(args[1])).toList();
        return super.onTabComplete(sender, command, alias, args);
    }
}
