package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaMode;
import me.zeronull.spawnarena.SavableArena;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ToggleArenaModeCommand extends ArenaTabComplete implements CommandExecutor {
    public ToggleArenaModeCommand() {
        super(0);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        // Random sumo on/off (takes effect from the next match), from anywhere
        if (args.length >= 2 && args[1].equalsIgnoreCase("random")) {
            final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(args[0]);
            if (!(found instanceof SavableArena savable)) {
                sender.sendMessage(ChatColor.RED + "That arena was not found.");
                return true;
            }
            final boolean on = !savable.isRandomSumo();
            // Only sumo arenas (touching water loses) have variants; turning it off always works
            if (on && !savable.isDeathOnTouchLiquid()) {
                sender.sendMessage(ChatColor.RED + "Random sumo is only for sumo arenas (where touching water loses), which "
                        + savable.getArenaName() + " isn't.");
                return true;
            }
            savable.setRandomSumo(on);
            savable.save(config -> config.randomSumo = on);
            sender.sendMessage(on
                    ? ChatColor.GREEN + "Random sumo is on for " + savable.getArenaName() + ": each match gets a random variant (from the next match)."
                    : ChatColor.YELLOW + "Random sumo is off for " + savable.getArenaName() + " (from the next match).");
            return true;
        }

        // Combo on/off for any arena (from the next hit), from anywhere
        if (args.length >= 2 && args[1].equalsIgnoreCase("combo")) {
            final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(args[0]);
            if (!(found instanceof SavableArena savable)) {
                sender.sendMessage(ChatColor.RED + "That arena was not found.");
                return true;
            }
            final boolean on = !savable.isCombo();
            savable.setCombo(on);
            savable.save(config -> config.combo = on);
            sender.sendMessage(on
                    ? ChatColor.GREEN + "Combo is on for " + savable.getArenaName() + ": no hit delay and softer knockback in every fight there."
                    : ChatColor.YELLOW + "Combo is off for " + savable.getArenaName() + ".");
            return true;
        }

        // Free-for-all on/off works from anywhere, the console included
        if (args.length >= 2 && args[1].equalsIgnoreCase("ffa")) {
            final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(args[0]);
            if (!(found instanceof SavableArena savable)) {
                sender.sendMessage(ChatColor.RED + "That arena was not found.");
                return true;
            }
            toggleFfa(sender, savable);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cUsage: /togglearenamode <name> [ffa|random|combo]"));
            return true;
        }

        final String arenaName = args[0];

        Player player = (Player) sender;

        if (!(player.getLocation().getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou must be at spawn to use this command!"));
            return true;
        }

        final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(arenaName);
        final SavableArena arena = found == null ? null : found.to(SavableArena.class);

        if (arena == null) {
            sender.sendMessage(ChatColor.RED + "That arena was not found.");
            return true;
        }

        if (!arena.isSetUp()) {
            sender.sendMessage(ChatColor.RED + "That arena has not been fully setup!");
            return true;
        }

        if (arena.isFfa()) {
            sender.sendMessage(ChatColor.RED + "Free-for-all arenas always run one session (SINGLE). Turn it off with /togglearenamode "
                    + arena.getArenaName() + " ffa.");
            return true;
        }

        if (arena.isCrystalPvp()) {
            sender.sendMessage(ChatColor.RED + "Crystal PvP arenas always run one fight at a time (SINGLE).");
            return true;
        }

        ArenaMode curentArenaMode = arena.getArenaMode();

        if (curentArenaMode == null)
            curentArenaMode = ArenaMode.SINGLE;

        final ArenaMode next = curentArenaMode.next();

        arena.setArenaMode(next.toString());
        arena.save(config -> config.arenaMode = next.toString());

        player.sendMessage(ChatColor.GREEN + String.format("Set arena mode to '%s'.", next.name()));

        return true;
    }

    /**
     * Switches free-for-all on or off, only while nothing at all is going on in the arena: no match counting down
     * or running, nobody in (or on their way into) a free-for-all, and no reset. Done under the arena's lock, which
     * joining also takes, so nobody can get in between the check and the change.
     */
    private static void toggleFfa(final CommandSender player, final SavableArena arena) {
        final boolean nowFfa;
        synchronized (arena) {
            final String busy = whyBusy(arena);
            if (busy != null) {
                player.sendMessage(ChatColor.RED + busy + " Try again once it's empty.");
                return;
            }
            nowFfa = !arena.isFfa();
            arena.setFfa(nowFfa);
            arena.save(config -> config.ffa = nowFfa);
        }
        if (nowFfa) {
            // Anyone waiting for a normal match here can't get one any more
            for (final Player p : arena.queue.clear())
                p.sendMessage(ChatColor.RED + "The " + arena.getArenaName() + " arena is now a free-for-all, so you were taken out of its queue. "
                        + "Run /joinarenaqueue " + arena.getArenaName() + " to go in.");
            player.sendMessage(ChatColor.GREEN + "Free-for-all is on for " + arena.getArenaName() + ": /joinarenaqueue "
                    + arena.getArenaName() + " now sends players straight in. Kills, deaths and kill streaks are counted apart; "
                    + "no wins, rewards or bets there.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Free-for-all is off for " + arena.getArenaName() + ": it's back to normal queued matches.");
        }
    }

    @Override
    public java.util.List<String> onTabComplete(final org.bukkit.command.CommandSender sender, final org.bukkit.command.Command command,
                                                final String label, final String[] args) {
        if (args.length == 2) {
            // "random" only for sumo arenas (or one that somehow has it on, to turn it off)
            final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(args[0]);
            final boolean sumo = arena != null && (arena.isDeathOnTouchLiquid() || arena.isRandomSumo());
            return (sumo ? java.util.List.of("ffa", "random", "combo") : java.util.List.of("ffa", "combo")).stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
        }
        return super.onTabComplete(sender, command, label, args);
    }

    private static String whyBusy(final SavableArena arena) {
        if (arena.isResetting())
            return "That arena is being reset.";
        for (final me.zeronull.spawnarena.Fight fight : arena.getFights()) {
            if (fight instanceof me.zeronull.spawnarena.FfaFight ffa) {
                final int in = ffa.getMembers().size();
                return in > 0 ? me.zeronull.spawnarena.Grammar.count(in, "player") + " " + (in == 1 ? "is" : "are")
                        + " in that arena's free-for-all right now." : "That arena's free-for-all is still ending.";
            }
            return fight.getState() == me.zeronull.spawnarena.FightState.COUNTDOWN
                    ? "A match in that arena is counting down." : "A match is going on in that arena.";
        }
        return null;
    }
}