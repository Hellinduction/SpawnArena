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

        // Looked up per payout: the economy provider (Cuy) may register after SpawnArena enables
        final RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            if (!warnedNoEconomy) {
                warnedNoEconomy = true;
                SpawnArena.INSTANCE.getLogger().warning("Vault has no economy provider registered; win rewards are not being paid.");
            }
            return;
        }
        final Economy economy = rsp.getProvider();

        final String arenaName = arena.getArenaName();
        final long now = System.currentTimeMillis();
        final long cooldownMillis = arena.getWinRewardCooldownMinutes() * 60_000L;
        final long last = SpawnArena.statsManager.getLastReward(winner.getUniqueId(), arenaName);
        final long remaining = last + cooldownMillis - now;

        if (remaining > 0) {
            winner.sendMessage(ChatColor.GRAY + "No win reward this time — you can earn it again in " + formatDuration(remaining) + ".");
            return;
        }

        final EconomyResponse response = economy.depositPlayer(winner, amount);
        if (!response.transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't pay " + winner.getName() + " their win reward: " + response.errorMessage);
            return;
        }

        SpawnArena.statsManager.setLastReward(winner.getUniqueId(), arenaName, now);
        winner.sendMessage(ChatColor.GREEN + "+" + economy.format(amount) + ChatColor.GRAY + " for winning! Next reward available in "
                + formatDuration(cooldownMillis) + ".");
    }

    private static String formatDuration(final long millis) {
        final long totalMinutes = Math.max(1, (millis + 59_999) / 60_000);
        final long hours = totalMinutes / 60;
        final long minutes = totalMinutes % 60;
        if (hours == 0) return minutes + "m";
        if (minutes == 0) return hours + "h";
        return hours + "h " + minutes + "m";
    }
}
