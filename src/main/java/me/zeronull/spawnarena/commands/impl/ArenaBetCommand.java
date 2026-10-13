package me.zeronull.spawnarena.commands.impl;

import me.zeronull.spawnarena.Bets;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.StatsManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * /arenabet <amount>   — bet on your next fight where your win reward is ready (e.g. 50k)
 * /arenabet off        — cancel it
 * /arenabet            — show your bet and how it works
 */
public final class ArenaBetCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("You must be a player to run this command!");
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault") || Bets.maxBet() <= 0) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Betting is turned off on this server."));
            return true;
        }

        final String max = StatsManager.formatMoney(Bets.maxBet());
        if (args.length == 0) {
            final Double current = Bets.pending(player);
            player.sendMessage(MiniMessage.miniMessage().deserialize(current == null
                            ? "<gray>No bet set. <gold>/arenabet <amount></gold> (up to <max>) bets on your next fight where your win reward is ready."
                            : "<gray>Your bet: <gold><bet></gold> on your next fight where your win reward is ready. <gold>/arenabet off</gold> to cancel.",
                    Placeholder.unparsed("max", max),
                    Placeholder.unparsed("bet", current == null ? "" : StatsManager.formatMoney(current))));
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>Win: your bet back <green>+<percent>%</green>. Lose: it's gone. Wins that don't pay out refund it.",
                    Placeholder.unparsed("percent", String.valueOf(SpawnArena.settings.betWinBonusPercent))));
            return true;
        }

        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("cancel")) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(Bets.clearPending(player)
                    ? "<yellow>Bet cancelled." : "<gray>You don't have a bet set."));
            return true;
        }

        final double amount = parseAmount(args[0]);
        if (amount <= 0) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Use an amount like 10000, 25k or 50k."));
            return true;
        }
        if (amount > Bets.maxBet()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>The most you can bet is <max>.", Placeholder.unparsed("max", max)));
            return true;
        }

        Bets.setPending(player, amount);
        final String when = Bets.rewardReadyAnywhere(player) ? "your next fight" : "your next fight where your win reward is ready";
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gold>Bet set: <bet></gold> <gray>on <when>. Win: <green>+<bonus></green> on top · lose: <red>-<bet></red>. Taken when the fight starts.",
                Placeholder.unparsed("bet", StatsManager.formatMoney(amount)),
                Placeholder.unparsed("bonus", StatsManager.formatMoney(Bets.bonusFor(amount))),
                Placeholder.unparsed("when", when)));
        return true;
    }

    /** "50000", "50k", "1.5k", "1m" -> the number; -1 if it isn't one. */
    static double parseAmount(final String raw) {
        String s = raw.trim().toLowerCase().replace(",", "").replace("$", "");
        double multiplier = 1;
        if (s.endsWith("k")) { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        try {
            final double value = Double.parseDouble(s) * multiplier;
            return Double.isFinite(value) ? Math.floor(value) : -1;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length == 1)
            return List.of("off", "10k", "25k", "50k").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        return Collections.emptyList();
    }
}
