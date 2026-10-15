package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Betting against the server: a player sets a bet with /arenabet, and it's placed on their next fight if
 * that fight can pay out. Win and they get it back plus a bonus (a percentage of it, not
 * double); lose and it's gone. Kept hard to abuse:
 * <ul>
 *   <li>one at a time, only for the next fight, and cancelled if that fight can't pay out or is a duel;</li>
 *   <li>capped (betMax), and the bonus counts toward the daily reward cap;</li>
 *   <li>a win that doesn't pay out (alts, same opponent too often, daily cap) refunds the bet instead;</li>
 *   <li>held bets are recorded in the database, so a crash or shutdown mid-fight refunds them.</li>
 * </ul>
 * Only touch this class after checking Vault is enabled — it links against Vault's API.
 */
public final class Bets {
    private Bets() {}

    /** Bets set with /arenabet, waiting for a fight where the player's reward is ready. Used once. */
    private static final Map<UUID, Double> PENDING = new ConcurrentHashMap<>();

    public static Double pending(final Player player) {
        return PENDING.get(player.getUniqueId());
    }

    public static void setPending(final Player player, final double amount) {
        PENDING.put(player.getUniqueId(), amount);
    }

    public static boolean clearPending(final Player player) {
        return PENDING.remove(player.getUniqueId()) != null;
    }

    /** What the player has right now, or 0 if the economy isn't there. */
    public static double balance(final Player player) {
        final Economy economy = WinRewards.economy();
        return economy == null ? 0 : economy.getBalance(player);
    }

    public static double maxBet() {
        return SpawnArena.settings == null ? 0 : Math.max(0, SpawnArena.settings.betMax);
    }

    public static double bonusFor(final double bet) {
        final double percent = SpawnArena.settings == null ? 50 : SpawnArena.settings.betWinBonusPercent;
        return Math.floor(bet * Math.max(0, percent) / 100.0);
    }

    /** Whether this player's win reward in this arena is ready right now (what a bet needs). */
    public static boolean rewardReady(final Player player, final Arena arena) {
        final StatsManager stats = SpawnArena.statsManager;
        return arena.getWinReward() > 0 && !stats.hasReachedDailyCap(player.getUniqueId())
                && stats.rewardCooldownRemaining(player.getUniqueId(), arena) <= 0;
    }

    /** Whether their reward is ready in at least one arena (just for the wording of /arenabet). */
    public static boolean rewardReadyAnywhere(final Player player) {
        for (final Arena arena : SpawnArena.arenas)
            if (rewardReady(player, arena))
                return true;
        return false;
    }

    /**
     * At the start of every fight, on the player's thread. A bet is only ever for the fight right after it's set:
     * it's taken here if this fight can pay out, and cancelled otherwise (never saved for a later fight).
     */
    public static void placeIfReady(final Player player, final Fight fight) {
        final Double amount = PENDING.remove(player.getUniqueId());
        if (amount == null || maxBet() <= 0)
            return;

        final Economy economy = WinRewards.economy();
        final String why = economy == null ? "betting isn't available right now"
                : fight.isDuel() ? "duels don't take bets"
                : !rewardReady(player, fight.getArena()) ? "your win reward isn't ready in this arena" : null;
        if (why != null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>Your <gold><bet></gold> bet was cancelled: <why>. Bets only count for the fight right after you set them.",
                    Placeholder.unparsed("bet", StatsManager.formatMoney(amount)),
                    Placeholder.unparsed("why", why)));
            return;
        }

        if (!economy.has(player, amount)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>You don't have <bet> any more, so your bet was cancelled.",
                    Placeholder.unparsed("bet", StatsManager.formatMoney(amount))));
            return;
        }
        final EconomyResponse taken = economy.withdrawPlayer(player, amount);
        if (!taken.transactionSuccess()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Couldn't take your bet, so it was cancelled."));
            return;
        }

        SpawnArena.statsManager.holdBet(player.getUniqueId(), amount);
        fight.bets().put(player.getUniqueId(), amount);
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gold>Bet placed: <bet>!</gold> <gray>Win to get it back <green>+<bonus></green> · lose and it's gone.",
                Placeholder.unparsed("bet", StatsManager.formatMoney(amount)),
                Placeholder.unparsed("bonus", StatsManager.formatMoney(bonusFor(amount)))));
    }

    /**
     * Settles a counted fight's bets. {@code paid} is what each winner's win reward paid (0 = it didn't),
     * a win that didn't pay refunds the bet.
     */
    public static void settle(final Fight fight, final List<Player> winners, final Map<UUID, Double> paid, final List<Player> losers) {
        final Economy economy = WinRewards.economy();
        final StatsManager stats = SpawnArena.statsManager;

        for (final Player winner : winners) {
            final Double bet = fight.bets().remove(winner.getUniqueId());
            if (bet == null)
                continue;
            if (paid.getOrDefault(winner.getUniqueId(), 0.0) <= 0) {
                refund(winner, bet, "your win didn't pay out this time");
                continue;
            }

            // The bonus counts toward the daily cap like any other arena earnings
            double bonus = bonusFor(bet);
            final double cap = stats.dailyCap();
            if (cap > 0)
                bonus = Math.max(0, Math.min(bonus, cap - stats.getEarnedToday(winner.getUniqueId())));

            if (economy == null || !economy.depositPlayer(winner, bet + bonus).transactionSuccess()) {
                SpawnArena.INSTANCE.getLogger().severe("Couldn't pay " + winner.getName() + " their bet back (" + bet + " + " + bonus
                        + "); it's still recorded as held and will be refunded on the next restart.");
                continue;
            }
            stats.releaseBet(winner.getUniqueId());
            if (bonus > 0) {
                stats.addEarnedToday(winner.getUniqueId(), bonus);
                stats.logBetBonus(winner.getUniqueId(), fight.getArena().getArenaName(), bonus);
            }
            winner.sendMessage(MiniMessage.miniMessage().deserialize(
                    bonus > 0
                            ? "<green>+<bonus> bet bonus!</green> <gray>(your <bet> bet is back too)"
                            : "<gray>Your <bet> bet is back (no bonus: you've hit today's limit).",
                    Placeholder.unparsed("bonus", StatsManager.formatMoney(bonus)),
                    Placeholder.unparsed("bet", StatsManager.formatMoney(bet))));
        }

        final double tps = fight.averageTps();
        for (final Player loser : losers) {
            final Double bet = fight.bets().remove(loser.getUniqueId());
            if (bet == null)
                continue;
            stats.releaseBet(loser.getUniqueId());

            // A laggy fight gives part of a lost bet back (not to quitters)
            final double back = lagRefundFor(bet, tps);
            if (back > 0 && !fight.hasQuit(loser)) {
                if (economy != null && economy.depositPlayer(loser, back).transactionSuccess()) {
                    loser.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<red>-<lost>: you lost your bet.</red> <gray>The server was lagging (<tps> TPS), so <back> came back.",
                            Placeholder.unparsed("lost", StatsManager.formatMoney(bet - back)),
                            Placeholder.unparsed("tps", String.format("%.1f", tps)),
                            Placeholder.unparsed("back", StatsManager.formatMoney(back))));
                    continue;
                }
                SpawnArena.INSTANCE.getLogger().severe("Couldn't give " + loser.getName() + " their " + back + " lag refund.");
            }
            loser.sendMessage(MiniMessage.miniMessage().deserialize("<red>-<bet>: you lost your bet.",
                    Placeholder.unparsed("bet", StatsManager.formatMoney(bet))));
        }
    }

    /** How much of a lost bet comes back when the fight's arena averaged this TPS (-1 = couldn't tell): 0 if it wasn't laggy. */
    static double lagRefundFor(final double bet, final double tps) {
        if (SpawnArena.settings == null || tps < 0)
            return 0;
        final double below = SpawnArena.settings.lagRefundBelowTps;
        final int percent = SpawnArena.settings.lagRefundPercent;
        if (below <= 0 || percent <= 0 || tps >= below)
            return 0;
        return Math.floor(bet * Math.min(100, percent) / 100.0);
    }

    /** Gives back one fighter's bet now (e.g. their connection dropped while their teammate fights on). */
    public static void refundOne(final Fight fight, final Player player, final String reason) {
        final Double bet = fight.bets().remove(player.getUniqueId());
        if (bet != null)
            refund(player, bet, reason);
    }

    /** Gives back every bet still held in this fight (not counted, cancelled, shut down...). */
    public static void refundAll(final Fight fight, final String reason) {
        for (final Map.Entry<UUID, Double> entry : Map.copyOf(fight.bets()).entrySet()) {
            fight.bets().remove(entry.getKey());
            final Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null)
                refund(player, entry.getValue(), reason);
            else
                refundOffline(entry.getKey(), entry.getValue());
        }
    }

    private static void refund(final Player player, final double bet, final String reason) {
        final Economy economy = WinRewards.economy();
        if (economy == null || !economy.depositPlayer(player, bet).transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().severe("Couldn't refund " + player.getName() + "'s " + bet
                    + " bet; it's still recorded as held and will be refunded on the next restart.");
            return;
        }
        SpawnArena.statsManager.releaseBet(player.getUniqueId());
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>Your <bet> bet was refunded: <reason>.",
                Placeholder.unparsed("bet", StatsManager.formatMoney(bet)), Placeholder.unparsed("reason", reason)));
    }

    private static void refundOffline(final UUID uuid, final double bet) {
        final Economy economy = WinRewards.economy();
        if (economy == null || !economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), bet).transactionSuccess()) {
            SpawnArena.INSTANCE.getLogger().severe("Couldn't refund " + uuid + "'s " + bet + " bet; it'll be retried on the next restart.");
            return;
        }
        SpawnArena.statsManager.releaseBet(uuid);
    }

    /** On startup: refunds bets left held by fights that never finished (crash or shutdown). */
    public static void refundLeftovers() {
        final Economy economy = WinRewards.economy();
        if (economy == null)
            return; // still recorded; tried again next start
        for (final Map.Entry<UUID, Double> entry : SpawnArena.statsManager.takeHeldBets().entrySet()) {
            if (economy.depositPlayer(Bukkit.getOfflinePlayer(entry.getKey()), entry.getValue()).transactionSuccess()) {
                SpawnArena.INSTANCE.getLogger().info("Refunded a " + entry.getValue() + " bet to " + entry.getKey() + " (their fight never finished).");
            } else {
                SpawnArena.statsManager.holdBet(entry.getKey(), entry.getValue()); // keep it for next time
                SpawnArena.INSTANCE.getLogger().severe("Couldn't refund " + entry.getKey() + "'s " + entry.getValue() + " bet; will retry next start.");
            }
        }
    }
}
