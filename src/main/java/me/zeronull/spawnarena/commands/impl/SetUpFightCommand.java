package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.ArenaMode;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.commands.ArenaTabComplete;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * /setupfight <arena> <p1> <p2>          — 1v1 between p1 and p2
 * /setupfight <arena> <p1> <p2> <p3> <p4> — 2v2: p1 + p2 against p3 + p4
 */
public final class SetUpFightCommand extends ArenaTabComplete implements CommandExecutor {
    public SetUpFightCommand() {
        super(0, true, 5);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length != 3 && args.length != 5) {
            // Nudge towards the nearest valid form
            final int players = Math.max(0, args.length - 1);
            if (players == 1)
                sender.sendMessage(ChatColor.RED + "A 1v1 needs 2 players: /setupfight " + args[0] + " " + args[1] + " <p2>");
            else if (players == 3)
                sender.sendMessage(ChatColor.RED + "A 2v2 needs 4 players: /setupfight " + args[0] + " "
                        + args[1] + " " + args[2] + " " + args[3] + " <p4>");
            else if (players > 4)
                sender.sendMessage(ChatColor.RED + "That's " + players + " players; fights are 1v1 (2 players) or 2v2 (4 players).");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&cUsage: /setupfight <arena> <p1> <p2>  &7or (2v2)  &c/setupfight <arena> <p1> <p2> <p3> <p4>"));
            return true;
        }

        if (!SpawnArena.settings.arenasOpen) {
            sender.sendMessage(ChatColor.RED + "The arenas are closed. Open them with /arenalock off first.");
            return true;
        }

        final Arena arena = SpawnArena.arenas.of(args[0]);
        if (arena == null) {
            sender.sendMessage(ChatColor.RED + "That arena was not found.");
            return true;
        }
        if (!arena.isSetUp()) {
            sender.sendMessage(ChatColor.RED + "That arena has not been fully setup!");
            return true;
        }
        if (arena.isFfa()) {
            sender.sendMessage(ChatColor.RED + "That arena is a free-for-all: players join it with /joinarenaqueue " + arena.getArenaName() + ".");
            return true;
        }

        final List<Player> players = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            final Player p = Bukkit.getPlayerExact(args[i]);
            if (p == null) {
                sender.sendMessage(ChatColor.RED + args[i] + " isn't online.");
                return true;
            }
            players.add(p);
        }
        if (new HashSet<>(players).size() != players.size()) {
            sender.sendMessage(ChatColor.RED + "The same player can't be in the fight twice.");
            return true;
        }
        for (final Player p : players) {
            if (SpawnArena.arenas.hasFighter(p)) {
                sender.sendMessage(ChatColor.RED + p.getName() + " is already in a fight.");
                return true;
            }
        }

        for (final Player p : players) {
            if (SpawnArena.arenas.isInQueue(p))
                SpawnArena.arenas.fromQueued(p).queue.removePlayer(p);
        }

        if (arena.getArenaMode() == ArenaMode.SINGLE && !arena.getFights().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "That arena already has a fight going on.");
            return true;
        }

        if (players.size() == 2) {
            // Started directly (the queue now gives players who've waited long a turn first)
            new Fight().initiateFight(players.get(0), players.get(1), arena);
            sender.sendMessage(ChatColor.GREEN + String.format("Set up a 1v1: %s vs %s. Starting in " + me.zeronull.spawnarena.Fight.countdownSeconds() + " seconds.",
                    players.get(0).getName(), players.get(1).getName()));
            return true;
        }
        new Fight().initiateTeamFight(players.subList(0, 2), players.subList(2, 4), arena);
        sender.sendMessage(ChatColor.GREEN + String.format("Set up a 2v2: %s & %s vs %s & %s. Starting in " + me.zeronull.spawnarena.Fight.countdownSeconds() + " seconds.",
                players.get(0).getName(), players.get(1).getName(), players.get(2).getName(), players.get(3).getName()));
        return true;
    }
}
