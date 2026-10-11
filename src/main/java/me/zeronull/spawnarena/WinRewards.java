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
        final double amount = arena.getWinReward();
        if (amount <= 0)
            return;

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
        winner.sendMessage(ChatColor.GREEN + "+" + economy.format(amount) + ChatColor.GRAY + " for winning!");
    }

    /** The economy's own money format (e.g. "$5,000.00"), or null without an economy provider. */
    public static String formatAmount(final double amount) {
        final Economy economy = economy();
        return economy == null ? null : economy.format(amount);
    }

    // Looked up each time: the economy provider (Cuy) may register after SpawnArena enables
    private static Economy economy() {
        final RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }
}
