package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * What each player did in one fight (filled in by FightStatsListener), shown when hovering over the result
 * message in chat. Only what applies to the arena is shown: no damage in sumo, no crystals outside crystal PvP,
 * and nothing that stayed at zero.
 */
public final class FightStats {
    private static final class Line {
        final DoubleAdder dealt = new DoubleAdder(), taken = new DoubleAdder();
        final AtomicInteger hits = new AtomicInteger(), melee = new AtomicInteger(), swings = new AtomicInteger(), crits = new AtomicInteger(), crystals = new AtomicInteger(),
                anchors = new AtomicInteger(), totems = new AtomicInteger(), gapples = new AtomicInteger();
        /** The other side hit or hurt them at some point (sumo knockback counts) */
        volatile boolean touched;
    }

    private final Map<UUID, Line> lines = new ConcurrentHashMap<>();
    private volatile long startedAt;

    private Line of(final Player player) {
        return this.lines.computeIfAbsent(player.getUniqueId(), k -> new Line());
    }

    /** When the fight went live (the duration counts from here). */
    void start() {
        this.startedAt = System.currentTimeMillis();
    }

    /** {@code amount} of health {@code victim} lost; {@code attacker} (if any, and an opponent) dealt it. */
    public void damage(final Player attacker, final Player victim, final double amount, final boolean hit, final boolean melee, final boolean crit) {
        this.of(victim).taken.add(amount);
        if (attacker == null || attacker.equals(victim))
            return;
        this.of(victim).touched = true;
        final Line line = this.of(attacker);
        line.dealt.add(amount);
        if (hit)
            line.hits.incrementAndGet();
        if (melee)
            line.melee.incrementAndGet();
        if (crit)
            line.crits.incrementAndGet();
    }

    /** Nobody on the other side ever hit or hurt any of these players: they went out on their own. */
    public boolean untouched(final List<Player> players) {
        for (final Player p : players) {
            final Line line = this.lines.get(p.getUniqueId());
            if (line != null && line.touched)
                return false;
        }
        return true;
    }

    public void swung(final Player player) { this.of(player).swings.incrementAndGet(); }
    public void crystalPlaced(final Player player) { this.of(player).crystals.incrementAndGet(); }
    public void anchorSetOff(final Player player) { this.of(player).anchors.incrementAndGet(); }
    public void totemPopped(final Player player) { this.of(player).totems.incrementAndGet(); }
    public void gappleEaten(final Player player) { this.of(player).gapples.incrementAndGet(); }

    /** The hover card: duration, then each player (winners first) with only the stats that apply and aren't zero. */
    public Component card(final Fight fight, final List<Player> winners, final List<Player> losers) {
        return this.card(fight, winners, losers, java.util.Map.of());
    }

    /** With each player's Elo after the fight and how much it moved ({new, change}), where it counted. */
    public Component card(final Fight fight, final List<Player> winners, final List<Player> losers, final java.util.Map<java.util.UUID, int[]> elo) {
        final Arena arena = fight.getArena();
        final boolean damageOn = arena.isAllowDamage() && !arena.isDeathOnTouchLiquid();
        final boolean crystal = arena.isCrystalPvp();
        final StringBuilder text = new StringBuilder("<gold><bold>Fight stats</bold></gold>");
        if (this.startedAt > 0)
            text.append(" <dark_gray>·</dark_gray> <gray>").append(duration(System.currentTimeMillis() - this.startedAt)).append("</gray>");

        final List<Player> everyone = new ArrayList<>(winners);
        everyone.addAll(losers);
        final List<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> names = new ArrayList<>();
        for (int i = 0; i < everyone.size(); i++) {
            final Player p = everyone.get(i);
            final boolean won = i < winners.size();
            final Line line = this.lines.getOrDefault(p.getUniqueId(), new Line());
            names.add(Placeholder.unparsed("p" + i, p.getName()));

            text.append("\n").append(won ? "<green>" : "<red>").append("<p").append(i).append(">").append(won ? "</green>" : "</red>");
            if (damageOn && won && fight.isFighter(p) && p.isOnline())
                text.append(" <gray>(").append(hearts(p.getHealth() + p.getAbsorptionAmount())).append("❤ left)</gray>");
            final int[] rating = elo.get(p.getUniqueId());
            if (rating != null)
                text.append(" <dark_gray>·</dark_gray> <gray>Elo <white>").append(rating[0]).append("</white> ")
                        .append(rating[1] >= 0 ? "<green>+" + rating[1] + "</green>" : "<red>" + rating[1] + "</red>");

            final List<String> parts = new ArrayList<>();
            if (damageOn) {
                parts.add("dealt <white>" + hearts(line.dealt.sum()) + "❤</white>");
                parts.add("taken <white>" + hearts(line.taken.sum()) + "❤</white>");
            }
            if (line.hits.get() > 0 || !damageOn)
                parts.add("hits <white>" + line.hits.get() + "</white>"
                        + (damageOn && line.crits.get() > 0 ? " (<white>" + line.crits.get() + "</white> crits)" : ""));
            // Melee only (an arrow isn't a swing), and not in crystal PvP, where most swings break crystals and blocks
            if (!crystal && line.swings.get() > 0) {
                parts.add("swings <white>" + line.swings.get() + "</white>");
                parts.add("accuracy <white>" + Math.min(100, Math.round(line.melee.get() * 100.0 / line.swings.get())) + "%</white>");
            }
            if (crystal && line.crystals.get() > 0) parts.add("crystals <white>" + line.crystals.get() + "</white>");
            if (crystal && line.anchors.get() > 0) parts.add("anchors <white>" + line.anchors.get() + "</white>");
            if (line.totems.get() > 0) parts.add("totems <white>" + line.totems.get() + "</white>");
            if (line.gapples.get() > 0) parts.add("gapples <white>" + line.gapples.get() + "</white>");
            text.append("\n  <gray>").append(String.join(" <dark_gray>·</dark_gray> ", parts)).append("</gray>");
        }
        return MiniMessage.miniMessage().deserialize(text.toString(),
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(names));
    }

    /** Health points as hearts, one decimal at most: 31 -> "15.5". */
    private static String hearts(final double health) {
        final double h = Math.round(health / 2 * 10) / 10.0;
        return h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(h);
    }

    private static String duration(final long millis) {
        final long s = Math.max(0, millis / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }
}
