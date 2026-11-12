package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaMode;
import me.zeronull.spawnarena.CrystalCopies;
import me.zeronull.spawnarena.CrystalCopy;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /crystalcopies                          — every MULTI crystal arena's copies: ready, a match on, being put back, or
 *                                           (where the server can't make worlds) still waited for
 * /crystalcopies setup &lt;arena&gt;           — looks for its copies' worlds again (made since, e.g. with the Worlds plugin)
 * /crystalcopies reset &lt;arena&gt; &lt;n|all&gt;   — copies the arena into that copy (or every free one) again, from scratch
 * Works from the console too.
 */
public final class CrystalCopiesCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            status(sender);
            return true;
        }
        final String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("setup") && !action.equals("reset")) {
            sender.sendMessage(ChatColor.RED + "Usage: /crystalcopies [setup <arena> | reset <arena> <number|all>]");
            return true;
        }
        final SavableArena real = args.length < 2 ? null : multi(args[1]);
        if (real == null) {
            sender.sendMessage(ChatColor.RED + (args.length < 2 ? "Which arena? " : "That isn't a MULTI crystal arena. ")
                    + "MULTI crystal arenas: " + String.join(", ", names()));
            return true;
        }

        if (action.equals("setup")) {
            sender.sendMessage(ChatColor.YELLOW + "Looking for " + real.getArenaName() + "'s copies' worlds...");
            CrystalCopies.setUp(real, false).whenComplete((ok, error) -> Threads.global(() -> {
                if (error != null)
                    sender.sendMessage(ChatColor.RED + "Couldn't set them up: " + error.getMessage());
                else
                    status(sender, real);
            }));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crystalcopies reset " + real.getArenaName() + " <number|all>");
            return true;
        }
        final List<CrystalCopy> chosen = new ArrayList<>();
        for (final CrystalCopy copy : CrystalCopies.of(real))
            if (args[2].equalsIgnoreCase("all") ? copy.getFights().isEmpty() && !copy.isResetting() : args[2].equals(String.valueOf(copy.index())))
                chosen.add(copy);
        if (chosen.isEmpty()) {
            sender.sendMessage(ChatColor.RED + (args[2].equalsIgnoreCase("all") ? "None of " + real.getArenaName() + "'s copies are free right now."
                    : real.getArenaName() + " has no copy " + args[2] + "."));
            return true;
        }
        for (final CrystalCopy copy : chosen) {
            final String name = "#" + copy.index() + " (" + copy.world().getName() + ")";
            sender.sendMessage(ChatColor.YELLOW + "Copying " + real.getArenaName() + " into copy " + name + " again...");
            CrystalCopies.recopy(copy).whenComplete((ok, error) -> Threads.global(() -> sender.sendMessage(error == null
                    ? ChatColor.GREEN + "Copy " + name + " is a fresh copy of " + real.getArenaName() + " again."
                    : ChatColor.RED + "Couldn't reset copy " + name + ": " + rootMessage(error))));
        }
        return true;
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null && error.getCause() != error)
            error = error.getCause();
        return error.getMessage();
    }

    private static void status(final CommandSender sender) {
        final List<SavableArena> arenas = multis();
        if (arenas.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "There are no MULTI crystal arenas"
                    + (CrystalCopies.supported() ? "." : " (MULTI needs WorldGuard and WorldEdit)."));
            return;
        }
        for (final SavableArena real : arenas)
            status(sender, real);
    }

    private static void status(final CommandSender sender, final SavableArena real) {
        final List<CrystalCopy> copies = CrystalCopies.of(real);
        final int wanted = Math.max(1, SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalMultiMatches);
        sender.sendMessage(ChatColor.GOLD + real.getArenaName() + ": " + ChatColor.WHITE + copies.size() + "/" + wanted + ChatColor.GOLD + " copies"
                + (CrystalCopies.canCreateWorlds() ? "" : CrystalCopies.viaWorldsPlugin() ? ChatColor.GRAY + " (worlds made through the Worlds plugin)"
                        : ChatColor.GRAY + " (this server can't make worlds: they're made beforehand, or install the Worlds plugin)"));
        for (final CrystalCopy copy : copies) {
            final String state = !copy.getFights().isEmpty() ? ChatColor.AQUA + "a match on"
                    : copy.isResetting() ? ChatColor.YELLOW + "being put back"
                    : ChatColor.GREEN + "ready";
            sender.sendMessage(ChatColor.GRAY + "  #" + copy.index() + " " + copy.world().getName() + ": " + state);
        }
        for (final int index : CrystalCopies.waitingFor(real))
            sender.sendMessage(ChatColor.GRAY + "  #" + index + " " + ChatColor.RED + "waiting for its world ("
                    + CrystalCopies.worldName(real, index) + ") to be made");
    }

    private static List<SavableArena> multis() {
        final List<SavableArena> found = new ArrayList<>();
        if (SpawnArena.arenas != null)
            for (final Arena arena : SpawnArena.arenas)
                if (arena instanceof SavableArena real && real.isCrystalPvp() && real.getArenaMode() == ArenaMode.MULTI)
                    found.add(real);
        return found;
    }

    private static SavableArena multi(final String name) {
        for (final SavableArena real : multis())
            if (real.getArenaName().equalsIgnoreCase(name))
                return real;
        return null;
    }

    private static List<String> names() {
        final List<String> names = new ArrayList<>();
        for (final SavableArena real : multis())
            names.add(real.getArenaName());
        return names.isEmpty() ? List.of("none") : names;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> options = new ArrayList<>();
        if (args.length == 1)
            options.addAll(List.of("setup", "reset"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("setup") || args[0].equalsIgnoreCase("reset")))
            options.addAll(names());
        else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            final SavableArena real = multi(args[1]);
            if (real != null) {
                for (final CrystalCopy copy : CrystalCopies.of(real))
                    options.add(String.valueOf(copy.index()));
                options.add("all");
            }
        }
        final String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
