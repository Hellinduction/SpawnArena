package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.*;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * /joinarenaqueue <arena>                — joins the 1v1 queue straight away; again to leave (the original NPC command)
 * /joinarenaqueue <arena> <1v1|2v2|auto> — joins that queue straight away; again to leave
 * /joinarenaqueue <arena> menu           — opens the 1v1 / Auto / 2v2 menu
 * Anything but 1v1 (2v2, auto, the menu) needs {@link #MODES_PERMISSION}.
 */
public class JoinArenaQueueCommand extends ArenaTabComplete implements CommandExecutor {
    /** 2v2, auto and the queue menu; without it, players can only queue 1v1. */
    public static final String MODES_PERMISSION = "spawnarena.joinarenaqueue.modes";

    public JoinArenaQueueCommand() {
        super(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }

        // In a countdown: running it again backs out (nobody's stats change; the others are requeued)
        if (SpawnArena.arenas.hasFighter(player)) {
            final Arena current = SpawnArena.arenas.of(player);
            final me.zeronull.spawnarena.Fight fight = current == null ? null : current.getFight(player);
            if (fight != null && fight.getState() == me.zeronull.spawnarena.FightState.COUNTDOWN) {
                if (fight.cancelBecause(player, player.getName() + " left the queue, so the fight was cancelled.")) {
                    player.sendMessage(ChatColor.YELLOW + "You left the queue.");
                    return true;
                }
                player.sendMessage(ChatColor.RED + "Too late, the fight is starting!");
                return true;
            }
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cUsage: /joinarenaqueue <arena> [1v1|2v2|auto|menu]"));
            return true;
        }

        final Arena arena = SpawnArena.arenas.of(args[0]);
        if (arena == null) {
            sender.sendMessage(ChatColor.RED + "That arena was not found.");
            return true;
        }

        // Free-for-all: straight in, whatever mode was asked for (the setting is checked again, under the
        // arena's lock, when they're let in)
        if (arena.isFfa()) {
            me.zeronull.spawnarena.FfaFight.join(player, arena);
            return true;
        }

        if (args.length >= 2 && !args[1].equalsIgnoreCase("1v1") && !player.hasPermission(MODES_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You can only queue 1v1 here: /joinarenaqueue " + arena.getArenaName());
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("menu")) {
            // Already queued here: the menu shows which queue they're in and lets them leave or switch. A fight already
            // running in the arena doesn't stop it opening (they can look, joining from it is still refused)
            final String problem = arena.queue.playerInQueue(player) ? null : cantJoin(player, arena, false);
            if (problem != null) {
                sender.sendMessage(ChatColor.RED + problem);
                return true;
            }
            QueueMenu.open(player, arena);
            return true;
        }

        // Running it again while queued here leaves (auto and no mode leave whichever queue they're in)
        final FightMode current = arena.queue.modeOf(player);
        final boolean anyMode = args.length < 2 || args[1].equalsIgnoreCase("auto");
        if (current != null && (anyMode || current == FightMode.fromLabel(args[1]))) {
            arena.queue.removePlayer(player);
            player.sendMessage(ChatColor.YELLOW + "You've been removed from the queue!");
            return true;
        }

        final FightMode mode = args.length < 2 ? FightMode.ONE_V_ONE
                : args[1].equalsIgnoreCase("auto") ? QueueMenu.autoMode(arena) : FightMode.fromLabel(args[1]);
        if (mode == null) {
            sender.sendMessage(ChatColor.RED + "Unknown mode. Use 1v1, 2v2, auto or menu.");
            return true;
        }
        join(player, arena, mode);
        return true;
    }

    /** Why this player can't queue for this arena right now, or null if they can. */
    public static String cantJoin(final Player player, final Arena arena) {
        return cantJoin(player, arena, true);
    }

    /** As {@link #cantJoin(Player, Arena)}; {@code busy} false leaves out a fight already running in a SINGLE arena. */
    private static String cantJoin(final Player player, final Arena arena, final boolean busy) {
        if (!SpawnArena.settings.arenasOpen)
            return "The arenas are closed right now. Try again later!";
        if (arena.isLocked())
            return "That arena is closed right now. Try again later!";
        if (!arena.isSetUp())
            return "That arena has not been fully setup!";
        if (SpawnArena.arenas.isInQueue(player, arena.queue))
            return "You are already queued for another arena.";
        if (SpawnArena.arenas.hasFighter(player))
            return "You are already in a fight!";
        if (!player.getLocation().getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return "You must be at spawn to use this command!";
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return "You can't join while in spectator mode.";
        if (SpawnArena.settings.queueMaxDistance > 0 && arena.distanceFrom(player.getLocation()) > SpawnArena.settings.queueMaxDistance)
            return "You are too far from that arena!";
        if (busy && !arena.queue.playerInQueue(player) && arena.getArenaMode() == ArenaMode.SINGLE && !arena.getFights().isEmpty())
            return "There's already a fight in that arena. Wait for it to finish, then join!";
        return null;
    }

    /** Validates and joins (or switches to) the given mode's queue. A free-for-all arena is joined straight away. */
    public static void join(final Player player, final Arena arena, final FightMode mode) {
        if (arena.isFfa()) {
            me.zeronull.spawnarena.FfaFight.join(player, arena);
            return;
        }
        if (arena.queue.modeOf(player) == mode) {
            player.sendMessage(ChatColor.YELLOW + "You're already in the " + arena.getArenaName() + " " + mode.label()
                    + " queue. Run /joinarenaqueue " + arena.getArenaName() + " to leave.");
            return;
        }
        final String problem = cantJoin(player, arena);
        if (problem != null) {
            player.sendMessage(ChatColor.RED + problem);
            return;
        }
        addToQueue(arena, player, mode);
    }

    public static void addToQueue(final Arena arena, final Player player) {
        addToQueue(arena, player, FightMode.ONE_V_ONE);
    }

    public static void addToQueue(final Arena arena, final Player player, final FightMode mode) {
        // Checked against the arena under its lock: it can't become a free-for-all between the checks and this
        synchronized (arena) {
            if (arena.isFfa()) {
                player.sendMessage(ChatColor.RED + "That arena just became a free-for-all. Run /joinarenaqueue " + arena.getArenaName() + " again to go in.");
                return;
            }
            me.zeronull.spawnarena.DuelManager.cancelSentRequests(player, "joined a queue");
            arena.queue.addPlayerToQueue(player, mode);
        }

        // Joining can immediately start a match, which takes players back out of the queue
        if (arena.queue.playerInQueue(player)) {
            final int waiting = arena.queue.size(mode);
            final String message = mode == FightMode.ONE_V_ONE
                    ? (waiting <= 1
                        ? String.format("&aYou've joined the &e%s 1v1&a queue! &7Waiting for an opponent...", arena.getArenaName())
                        : String.format("&aYou've joined the &e%s 1v1&a queue! &7(%d players waiting, including you)", arena.getArenaName(), waiting))
                    : String.format("&aYou've joined the &e%s 2v2&a queue! &7(%d/%d players)", arena.getArenaName(), waiting, mode.players());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aYou've joined the queue! &7Match found, get ready!"));
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&eWarning! If you have issues (e.g. you lost your inventory or need it restored for another reason), &c&l&ndo NOT&e rejoin the arena. Tell staff, warn other players, and avoid dying an excessive number of times. Otherwise, your inventory logs will be lost!"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2)
            return (sender.hasPermission(MODES_PERMISSION) ? List.of("1v1", "2v2", "auto", "menu") : List.of("1v1")).stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        if (args.length > 2)
            return Collections.emptyList();
        return super.onTabComplete(sender, command, label, args);
    }
}
