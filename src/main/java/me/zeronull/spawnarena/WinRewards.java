package me.zeronull.spawnarena;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/** Only touch this class after checking Vault is enabled — it links against Vault's API. */
public final class WinRewards {
    private static boolean warnedNoEconomy;

    private WinRewards() {}

    /**
     * Pays the winner's reward if allowed. Only a successful payout sends a chat message; the reason a
     * win didn't pay is shown on the reward hologram (%spawnarena_reward_last_<arena>%) instead.
     */
    public static void reward(final Player winner, final Player loser, final Arena arena, final boolean alts) {
        if (arena.getWinReward() <= 0)
            return;

        final StatsManager stats = SpawnArena.statsManager;
        final String arenaName = arena.getArenaName();

        // Beating someone new (who isn't an alt) clears the same-opponent count, even if this win doesn't pay
        if (loser != null && !alts)
            stats.noteWinAgainst(winner.getUniqueId(), arenaName, loser.getUniqueId());

        final Economy economy = economy();
        if (economy == null) {
            if (!warnedNoEconomy) {
                warnedNoEconomy = true;
                SpawnArena.INSTANCE.getLogger().warning("Vault has no economy provider registered; win rewards are not being paid.");
            }
            return;
        }

        final String blocked = blockedReason(winner, loser, arena, alts);
        if (blocked != null) {
            stats.setLastResult(winner.getUniqueId(), arenaName, "Not paid: " + blocked);
            return;
        }

        // Checked after this win was recorded, so a win that takes the #1 streak already counts
        final boolean leader = stats.isStreakLeader(winner.getUniqueId(), arenaName);
        double amount = stats.rewardAmountFor(winner.getUniqueId(), arena);
        final double cap = stats.dailyCap();
        if (cap > 0)
            amount = Math.min(amount, cap - stats.getEarnedToday(winner.getUniqueId()));

        final EconomyResponse response = economy.depositPlayer(winner, amount);
        if (!response.transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't pay " + winner.getName() + " their win reward: " + response.errorMessage);
            return;
        }

        stats.recordPayout(winner.getUniqueId(), arenaName, System.currentTimeMillis(),
                loser == null ? null : loser.getUniqueId());
        stats.addEarnedToday(winner.getUniqueId(), amount);
        stats.setLastResult(winner.getUniqueId(), arenaName, "+" + StatsManager.formatMoney(amount));

        if (leader && arena.getLeaderRewardMultiplier() != 1)
            winner.sendMessage(ChatColor.GREEN + "+" + StatsManager.formatMoney(amount) + ChatColor.GRAY + " for winning! "
                    + ChatColor.GOLD + "(" + formatMultiplier(arena.getLeaderRewardMultiplier()) + " top win streak bonus)");
        else
            winner.sendMessage(ChatColor.GREEN + "+" + StatsManager.formatMoney(amount) + ChatColor.GRAY + " for winning!");
    }

    /** Why this win shouldn't pay, or null if it should. */
    private static String blockedReason(final Player winner, final Player loser, final Arena arena, final boolean alts) {
        final StatsManager stats = SpawnArena.statsManager;
        final UUID uuid = winner.getUniqueId();

        if (alts && SpawnArena.settings.blockSameIpRewards)
            return "same IP as your opponent";

        // Counted in payouts: the same opponent can only pay out sameOpponentLimit times in a row
        if (loser != null && stats.isBlockedAgainst(uuid, arena.getArenaName(), loser.getUniqueId()))
            return "beat someone new to earn again";

        if (stats.hasReachedDailyCap(uuid))
            return "daily limit reached";

        if (stats.rewardCooldownRemaining(uuid, arena) > 0)
            return "on cooldown";

        return null;
    }

    static String formatMultiplier(final double multiplier) {
        return (multiplier == Math.rint(multiplier) ? String.valueOf((long) multiplier) : String.valueOf(multiplier)) + "x";
    }

    /**
     * What the economy puts before and after the number, e.g. {"$", ""} for "$2.00" or {"", " coins"} for "2 coins".
     * Null when there's no economy provider or its format has no digits.
     */
    public static String[] currencyAffixes() {
        final Economy economy = economy();
        if (economy == null)
            return null;

        final String sample = economy.format(2);
        int first = -1, last = -1;
        for (int i = 0; i < sample.length(); i++) {
            if (Character.isDigit(sample.charAt(i))) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0)
            return null;

        return new String[]{sample.substring(0, first), sample.substring(last + 1)};
    }

    // Looked up each time: the economy provider (Cuy) may register after SpawnArena enables
    private static Economy economy() {
        final RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }
}
