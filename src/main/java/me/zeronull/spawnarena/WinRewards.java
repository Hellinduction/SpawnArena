package me.zeronull.spawnarena;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Only touch this class after checking Vault is enabled — it links against Vault's API. */
public final class WinRewards {
    private static boolean warnedNoEconomy;

    private WinRewards() {}

    public static void reward(final Player winner, final Arena arena) {
        if (arena.getWinReward() <= 0)
            return;

        // Checked after this win was recorded, so a win that takes the #1 streak already counts
        final boolean leader = SpawnArena.statsManager.isStreakLeader(winner.getUniqueId(), arena.getArenaName());
        final double amount = SpawnArena.statsManager.rewardAmountFor(winner.getUniqueId(), arena);

        final Economy economy = economy();
        if (economy == null) {
            if (!warnedNoEconomy) {
                warnedNoEconomy = true;
                SpawnArena.INSTANCE.getLogger().warning("Vault has no economy provider registered; win rewards are not being paid.");
            }
            return;
        }

        // Still on cooldown: say nothing — the reward hologram/placeholder shows the countdown
        if (SpawnArena.statsManager.rewardCooldownRemaining(winner.getUniqueId(), arena) > 0)
            return;

        final EconomyResponse response = economy.depositPlayer(winner, amount);
        if (!response.transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't pay " + winner.getName() + " their win reward: " + response.errorMessage);
            return;
        }

        SpawnArena.statsManager.setLastReward(winner.getUniqueId(), arena.getArenaName(), System.currentTimeMillis());
        if (leader && arena.getLeaderRewardMultiplier() != 1)
            winner.sendMessage(ChatColor.GREEN + "+" + StatsManager.formatMoney(amount) + ChatColor.GRAY + " for winning! "
                    + ChatColor.GOLD + "(" + formatMultiplier(arena.getLeaderRewardMultiplier()) + " top win streak bonus)");
        else
            winner.sendMessage(ChatColor.GREEN + "+" + StatsManager.formatMoney(amount) + ChatColor.GRAY + " for winning!");
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
