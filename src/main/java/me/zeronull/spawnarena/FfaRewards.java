package me.zeronull.spawnarena;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Free-for-all kill streak payouts (a free-for-all has no wins to pay for). Reaching a paid streak of 5, 15, 25... pays
 * ffaStreakReward and 10, 20, 30... ffaStreakRewardTens, each streak at most once every ffaStreakRewardCooldownMinutes
 * per player: to keep earning, they have to reach streaks they haven't been paid for lately. Only touch this class after
 * checking Vault is enabled: it links against Vault's API.
 */
public final class FfaRewards {
    private FfaRewards() {}

    /** Pays {@code killer} for reaching this paid streak, if it's a paying one and allowed. Off the main thread (it reads the database). */
    static void reachedStreak(final Player killer, final Arena arena, final int streak, final UUID victim) {
        final double full = arena.ffaRewardFor(streak);
        if (full <= 0)
            return;
        final StatsManager stats = SpawnArena.statsManager;
        final String arenaName = arena.getArenaName();
        final UUID uuid = killer.getUniqueId();
        final Economy economy = WinRewards.economy();
        if (economy == null)
            return;

        final long now = System.currentTimeMillis();
        final Long last = stats.ffaStreakPayouts(uuid, arenaName).get(streak);
        final long wait = arena.getFfaStreakRewardCooldownMinutes() * 60_000L;
        if (last != null && now - last < wait) {
            final String left = StatsManager.formatCountdown(last + wait - now);
            stats.setLastResult(uuid, arenaName, "Not paid: " + streak + " kill streak paid lately");
            killer.sendMessage(ChatColor.GRAY + "Your " + streak + " kill streak was paid out lately: it pays again in "
                    + ChatColor.WHITE + left + ChatColor.GRAY + ". Go for a longer one!");
            return;
        }

        double amount = full;
        final double cap = stats.dailyCap();
        if (cap > 0)
            amount = Math.min(amount, cap - stats.getEarnedToday(uuid));
        if (amount <= 0) {
            stats.setLastResult(uuid, arenaName, "Not paid: daily limit reached");
            WinRewards.tellDailyLimitOnce(killer);
            return;
        }

        final double paying = amount;
        final EconomyResponse response = Threads.callGlobal(() -> economy.depositPlayer(killer, paying));
        if (!response.transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't pay " + killer.getName() + " their kill streak reward: " + response.errorMessage);
            return;
        }
        stats.recordFfaStreakPayout(uuid, arenaName, streak, now);
        stats.addEarnedToday(uuid, amount);
        stats.logPayout(uuid, arenaName, amount, java.util.List.of(victim), false);
        stats.setLastResult(uuid, arenaName, "+" + StatsManager.formatMoney(amount) + " (" + streak + " kill streak)");
        killer.sendMessage(ChatColor.GREEN + "+" + StatsManager.formatMoney(amount) + ChatColor.GRAY + " for a "
                + ChatColor.WHITE + streak + ChatColor.GRAY + " kill streak!");
        if (stats.hasReachedDailyCap(uuid))
            WinRewards.tellDailyLimitOnce(killer);
    }
}
