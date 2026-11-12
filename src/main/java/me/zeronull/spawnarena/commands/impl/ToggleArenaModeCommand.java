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

        // Spleef on/off works from anywhere, the console included
        if (args.length >= 2 && args[1].equalsIgnoreCase("spleef")) {
            final me.zeronull.spawnarena.Arena found = SpawnArena.arenas.of(args[0]);
            if (!(found instanceof SavableArena savable)) {
                sender.sendMessage(ChatColor.RED + "That arena was not found.");
                return true;
            }
            toggleSpleef(sender, savable);
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
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cUsage: /togglearenamode <name> [ffa|random|combo|spleef]"));
            return true;
        }

        final String arenaName = args[0];

        Player player = (Player) sender;

        if (!Arena.isSpawnWorld(player.getWorld())) {
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

        // MULTI and free-for-all don't go together (a free-for-all is one session, in the arena itself): it goes off first
        if (arena.isFfa()) {
            synchronized (arena) {
                final String busy = whyBusy(arena);
                if (busy != null) {
                    sender.sendMessage(ChatColor.RED + arena.getArenaName() + " is a free-for-all, which has to go off for MULTI: "
                            + busy + " Try again once it's empty.");
                    return true;
                }
                arena.setFfa(false);
                arena.save(config -> config.ffa = false);
            }
            sender.sendMessage(ChatColor.YELLOW + "Free-for-all is off for " + arena.getArenaName() + " (it can't be MULTI as well).");
            redrawHolograms();
        }

        if (arena.usesCopies()) {
            if (!me.zeronull.spawnarena.CrystalCopies.supported()) {
                sender.sendMessage(ChatColor.RED + (arena.isSpleef() ? "Spleef" : "Crystal PvP")
                        + " arenas run one fight at a time (SINGLE) on this server: MULTI needs WorldGuard and WorldEdit.");
                return true;
            }
            if (arena.getArenaMode() == ArenaMode.MULTI) {
                if (me.zeronull.spawnarena.CrystalCopies.of(arena).stream().anyMatch(copy -> !copy.getFights().isEmpty())) {
                    sender.sendMessage(ChatColor.RED + "There are matches going on in " + arena.getArenaName() + "'s copies. Try again once they're over.");
                    return true;
                }
                arena.setArenaMode(ArenaMode.SINGLE.name());
                arena.save(config -> config.arenaMode = ArenaMode.SINGLE.name());
                me.zeronull.spawnarena.CrystalCopies.retire(arena);
                player.sendMessage(ChatColor.GREEN + "Set arena mode to 'SINGLE': matches are fought in the arena itself again, one at a time.");
                return true;
            }
            final int matches = SpawnArena.settings == null ? 3 : SpawnArena.settings.crystalMultiMatches;
            player.sendMessage(ChatColor.YELLOW + "Setting up " + matches + " copies of " + arena.getArenaName()
                    + " (each in a world of its own) and copying the arena into them...");
            me.zeronull.spawnarena.CrystalCopies.setUp(arena, true).whenComplete((ok, error) -> {
                arena.setArenaMode(ArenaMode.MULTI.name());
                arena.save(config -> config.arenaMode = ArenaMode.MULTI.name());
                player.sendMessage(error == null
                        ? ChatColor.GREEN + "Set arena mode to 'MULTI': up to " + me.zeronull.spawnarena.CrystalCopies.of(arena).size()
                                + " matches at once, each in its own copy of the arena."
                        : ChatColor.RED + "Couldn't set up the copies: " + error.getMessage());
            });
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
    /** Spleef on or off: fighters break the floor (see Spleef). Clearing fighters' inventories comes on with it. */
    private static void toggleSpleef(final CommandSender sender, final SavableArena arena) {
        if (arena.isCrystalPvp()) {
            sender.sendMessage(ChatColor.RED + arena.getArenaName() + " is a crystal PvP arena: an arena can't be both.");
            return;
        }
        final boolean nowSpleef;
        final boolean wasMulti;
        synchronized (arena) {
            final String busy = whyBusy(arena);
            if (busy != null) {
                sender.sendMessage(ChatColor.RED + busy + " Try again once it's empty.");
                return;
            }
            nowSpleef = !arena.isSpleef();
            // MULTI means something else for spleef (a copy of the arena per match, set up by /togglearenamode): back to
            // SINGLE either way, and a spleef arena's copies retired
            wasMulti = arena.getArenaMode() == ArenaMode.MULTI;
            if (wasMulti) {
                arena.setArenaMode(ArenaMode.SINGLE.name());
                arena.save(config -> config.arenaMode = ArenaMode.SINGLE.name());
                me.zeronull.spawnarena.CrystalCopies.retire(arena);
            }
            arena.setSpleef(nowSpleef);
            if (nowSpleef)
                arena.setShouldClearItems(true);
            arena.save(config -> {
                config.spleef = nowSpleef;
                if (nowSpleef)
                    config.shouldClearItems = true;
            });
        }
        sender.sendMessage(nowSpleef
                ? ChatColor.GREEN + "Spleef is on for " + arena.getArenaName() + ": fighters get a shovel and break the floor ("
                        + arena.getSpleefFloor() + ") to drop each other into the liquid at the bottom. It's put back after every match."
                : ChatColor.YELLOW + "Spleef is off for " + arena.getArenaName() + ".");
        if (wasMulti)
            sender.sendMessage(ChatColor.YELLOW + arena.getArenaName() + " is back to SINGLE (one match at a time)."
                    + (nowSpleef ? " /togglearenamode " + arena.getArenaName() + " makes it MULTI again, each match in a copy of its own." : ""));
    }

    private static void toggleFfa(final CommandSender player, final SavableArena arena) {
        // A MULTI crystal or spleef arena's real arena is its viewing arena (put back whenever it's changed): back to SINGLE first
        if (!arena.isFfa() && arena.usesCopies() && arena.getArenaMode() == ArenaMode.MULTI) {
            if (me.zeronull.spawnarena.CrystalCopies.of(arena).stream().anyMatch(copy -> !copy.getFights().isEmpty())) {
                player.sendMessage(ChatColor.RED + arena.getArenaName() + " is in MULTI mode, which has to go off for a free-for-all, "
                        + "and there are matches going on in its copies. Try again once they're over.");
                return;
            }
            arena.setArenaMode(ArenaMode.SINGLE.name());
            arena.save(config -> config.arenaMode = ArenaMode.SINGLE.name());
            me.zeronull.spawnarena.CrystalCopies.retire(arena);
            player.sendMessage(ChatColor.YELLOW + "Set " + arena.getArenaName() + " back to SINGLE (a free-for-all can't be MULTI as well).");
        }
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
                    + "no wins or bets there, and kill streaks of 5, 10, 15... pay instead of wins (ffaStreakReward in its file).");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Free-for-all is off for " + arena.getArenaName() + ": it's back to normal queued matches.");
        }
        redrawHolograms();
    }

    /** Stats holograms show different lines for a free-for-all: every SpawnArena hologram is redrawn with the current layout. */
    private static void redrawHolograms() {
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
            return;
        try {
            me.zeronull.spawnarena.LeaderboardHolograms.updateAll();
        } catch (final LinkageError | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't redraw the leaderboard holograms: " + e);
        }
    }

    @Override
    public java.util.List<String> onTabComplete(final org.bukkit.command.CommandSender sender, final org.bukkit.command.Command command,
                                                final String label, final String[] args) {
        if (args.length == 2) {
            // "random" only for sumo arenas (or one that somehow has it on, to turn it off)
            final me.zeronull.spawnarena.Arena arena = SpawnArena.arenas.of(args[0]);
            final boolean sumo = arena != null && (arena.isDeathOnTouchLiquid() || arena.isRandomSumo());
            return (sumo ? java.util.List.of("ffa", "random", "combo", "spleef") : java.util.List.of("ffa", "combo", "spleef")).stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
        }
        return super.onTabComplete(sender, command, label, args);
    }

    private static String whyBusy(final SavableArena arena) {
        if (arena.isResetting())
            return "That arena is being reset.";
        for (final me.zeronull.spawnarena.Fight fight : arena.allFights()) {
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