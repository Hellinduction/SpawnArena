package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Milestone matches (settings.yml milestoneRewards): an arena's 100th, 200th... 1,000th... counted match pays its winners
 * extra. One too short (milestoneMinSeconds) passes its milestone on to that arena's next match that isn't.
 */
public final class Milestones {
    private Milestones() {}

    /** What a match with this number pays as a milestone (the biggest milestone it's a multiple of), or 0. */
    static double rewardFor(final long number) {
        final Map<Integer, Double> rewards = SpawnArena.settings == null ? Map.of() : SpawnArena.settings.milestoneRewards;
        int best = 0;
        for (final Integer every : rewards.keySet())
            if (every != null && every > best && number % every == 0)
                best = every;
        return best == 0 ? 0 : Math.max(0, rewards.get(best));
    }

    /**
     * The milestone this match (number {@code number}, {@code seconds} long) wins, or 0: a milestone passed on from an
     * earlier match first, else its own number if that's one. Too short: passed on to the next. On the results thread.
     */
    static long due(final String arena, final long number, final long seconds) {
        final StatsManager stats = SpawnArena.statsManager;
        final long pending = stats.getPendingMilestone(arena);
        final long milestone = pending > 0 ? pending : rewardFor(number) > 0 ? number : 0;
        if (milestone == 0)
            return 0;
        final int min = SpawnArena.settings == null ? 0 : SpawnArena.settings.milestoneMinSeconds;
        if (seconds < min) {
            if (pending == 0)
                stats.setPendingMilestone(arena, milestone);
            return 0;
        }
        if (pending > 0)
            stats.setPendingMilestone(arena, 0);
        return milestone;
    }

    /** Pays a milestone to the winners still online, each told under their result. On the results thread. */
    static void pay(final Fight fight, final long milestone, final List<Player> winners, final List<Player> losers) {
        final double amount = rewardFor(milestone);
        if (amount <= 0)
            return;
        final String arenaName = fight.getArena().getArenaName();
        final String ordinal = String.format("%,d", milestone) + Grammar.ordinalSuffix(milestone);
        final List<UUID> beaten = losers.stream().map(Player::getUniqueId).toList();
        for (final Player winner : winners) {
            if (!winner.isOnline())
                continue;
            final boolean paid = Bukkit.getPluginManager().isPluginEnabled("Vault") && WinRewards.economy() != null
                    && Threads.callGlobal(() -> WinRewards.economy().depositPlayer(winner, amount).transactionSuccess());
            if (!paid) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't pay " + winner.getName() + " the " + ordinal + " " + arenaName + " match milestone (" + amount + ").");
                continue;
            }
            SpawnArena.statsManager.logPayout(winner.getUniqueId(), arenaName, amount, beaten, false);
            winner.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gradient:#FFD700:#FF8C00><bold>MILESTONE!</bold></gradient> <yellow>You won the <gold><bold><ordinal></bold></gold> <arena> match: <green><bold>+<amount></bold></green> <yellow>bonus!",
                    Placeholder.unparsed("amount", StatsManager.formatMoney(amount)), Placeholder.unparsed("ordinal", ordinal),
                    Placeholder.unparsed("arena", Arena.displayName(arenaName))));
        }
    }
}
