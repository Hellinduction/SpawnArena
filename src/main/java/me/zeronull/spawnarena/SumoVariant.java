package me.zeronull.spawnarena;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random sumo: with randomSumo on, each match (or free-for-all session) gets one of these at random, the same for
 * everyone in it. Ice Rink changes the arena's own blocks, so it's only picked where one match has the arena to itself.
 */
public enum SumoVariant {
    CLASSIC("Classic", "Slap Fish"),
    ARCHER("Archer", "Punch bow"),
    ICE("Ice Rink", "the floor is ice"),
    SNOWBALL("Snowball Fight", "snowballs"),
    ROD("Rod", "knockback fishing rod"),
    MOON("Moon Gravity", "jump high, fall slow"),
    SHRINK("Shrinking Platform", "the edges fall away");

    public final String title;
    public final String hint;

    /** Test hook: the next pick is this one (once). */
    public static volatile SumoVariant forceNext;

    SumoVariant(final String title, final String hint) {
        this.title = title;
        this.hint = hint;
    }

    /** A random variant for a match in this arena, or null if the arena doesn't use random sumo. */
    public static SumoVariant pickFor(final Arena arena) {
        return pickFor(arena, false);
    }

    /**
     * Shrinking Platform needs the arena to itself (the server won't let anyone fall through a block it still has, so
     * it can't be shown to just one match) and a match that ends, so never in MULTI arenas or free-for-alls.
     */
    public static SumoVariant pickFor(final Arena arena, final boolean ffa) {
        return pickFor(arena, ffa, List.of());
    }

    /**
     * As {@link #pickFor(Arena, boolean)}, for a match between these players: each player goes through every variant
     * before getting one again (other matches in the arena don't use up theirs). A free-for-all, where players come and
     * go, deals from the arena's own deck instead.
     */
    public static SumoVariant pickFor(final Arena arena, final boolean ffa, final java.util.Collection<java.util.UUID> players) {
        if (!arena.isRandomSumo() || !arena.isDeathOnTouchLiquid())
            return null;
        final SumoVariant forced = forceNext;
        if (forced != null) {
            forceNext = null;
            if ((forced != ICE || iceAllowed(arena)) && (forced != SHRINK || shrinkAllowed(arena, ffa)))
                return forced;
        }
        final List<SumoVariant> choices = new ArrayList<>(List.of(values()));
        if (!iceAllowed(arena))
            choices.remove(ICE);
        if (!shrinkAllowed(arena, ffa))
            choices.remove(SHRINK);
        if (!ffa && !players.isEmpty())
            return dealFor(players, choices);
        return deal(arena.getArenaName().toLowerCase(java.util.Locale.ROOT), choices);
    }

    /** The variants each player has had in their current round, and the last one they had. */
    private static final java.util.Map<java.util.UUID, java.util.EnumSet<SumoVariant>> HAD = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, SumoVariant> LAST_HAD = new java.util.HashMap<>();

    /**
     * One that none of these players has had this round if there is one, otherwise one the fewest of them have had;
     * never the one any of them just had, if that can be helped. A player who's had every variant allowed here starts a
     * new round.
     */
    private static synchronized SumoVariant dealFor(final java.util.Collection<java.util.UUID> players, final List<SumoVariant> allowed) {
        if (allowed.isEmpty())
            return CLASSIC;
        for (final java.util.UUID p : players) {
            final java.util.EnumSet<SumoVariant> had = HAD.computeIfAbsent(p, u -> java.util.EnumSet.noneOf(SumoVariant.class));
            if (had.containsAll(allowed))
                had.clear();
        }
        final java.util.Set<SumoVariant> justHad = java.util.EnumSet.noneOf(SumoVariant.class);
        for (final java.util.UUID p : players)
            if (LAST_HAD.get(p) != null)
                justHad.add(LAST_HAD.get(p));
        List<SumoVariant> best = new ArrayList<>();
        int bestScore = Integer.MAX_VALUE;
        for (final SumoVariant v : allowed) {
            int score = 0;
            for (final java.util.UUID p : players)
                if (HAD.get(p).contains(v))
                    score += 2;
            if (justHad.contains(v))
                score += 100; // never the one someone just had, unless every choice is
            if (score < bestScore) {
                bestScore = score;
                best = new ArrayList<>();
            }
            if (score == bestScore)
                best.add(v);
        }
        final SumoVariant picked = best.get(ThreadLocalRandom.current().nextInt(best.size()));
        for (final java.util.UUID p : players) {
            HAD.get(p).add(picked);
            LAST_HAD.put(p, picked);
        }
        return picked;
    }

    /** Each arena's shuffled deck of variants still to come, and the last one dealt. */
    private static final java.util.Map<String, java.util.ArrayDeque<SumoVariant>> DECKS = new java.util.HashMap<>();
    private static final java.util.Map<String, SumoVariant> LAST = new java.util.HashMap<>();

    /**
     * Like dealing from a shuffled deck holding each variant once: over a round every variant comes up once, so
     * nobody gets the same one again and again by chance. A new round is shuffled when the deck runs out (never
     * starting with the one just played); a variant not allowed right now is skipped and stays for later.
     */
    private static synchronized SumoVariant deal(final String arena, final List<SumoVariant> allowed) {
        final java.util.ArrayDeque<SumoVariant> deck = DECKS.computeIfAbsent(arena, a -> new java.util.ArrayDeque<>());
        for (final java.util.Iterator<SumoVariant> it = deck.iterator(); it.hasNext(); ) {
            final SumoVariant next = it.next();
            if (allowed.contains(next)) {
                it.remove();
                LAST.put(arena, next);
                return next;
            }
        }
        // Nothing left that's allowed here: a new round
        final List<SumoVariant> round = new ArrayList<>(List.of(values()));
        java.util.Collections.shuffle(round, ThreadLocalRandom.current());
        final SumoVariant last = LAST.get(arena);
        if (round.size() > 1 && round.get(0) == last)
            java.util.Collections.swap(round, 0, 1 + ThreadLocalRandom.current().nextInt(round.size() - 1));
        deck.clear();
        deck.addAll(round);
        final List<SumoVariant> fallback = allowed.isEmpty() ? List.of(CLASSIC) : allowed;
        for (final java.util.Iterator<SumoVariant> it = deck.iterator(); it.hasNext(); ) {
            final SumoVariant next = it.next();
            if (fallback.contains(next) && (next != last || fallback.size() == 1)) {
                it.remove();
                LAST.put(arena, next);
                return next;
            }
        }
        return fallback.get(0);
    }

    /** Real ice when one match has the arena to itself; shown only to that match's players in MULTI, if allowed. */
    private static boolean iceAllowed(final Arena arena) {
        return arena.getArenaMode() == ArenaMode.SINGLE || SpawnArena.settings == null || SpawnArena.settings.sumoIceInMulti;
    }

    private static boolean shrinkAllowed(final Arena arena, final boolean ffa) {
        return !ffa && arena.getArenaMode() == ArenaMode.SINGLE;
    }

    /** Whether this arena's Ice Rink is shown only to the match's players (MULTI) rather than real ice. */
    public static boolean iceIsClientSide(final Arena arena) {
        return arena.getArenaMode() != ArenaMode.SINGLE;
    }

    /** How high a jump goes with no jump boost, then with each level of it (in blocks, roughly). */
    private static final double[] JUMP_HEIGHTS = {1.25, 1.84, 2.53, 3.31, 4.17};
    /** Moon Gravity leaves at least this much room above the top of a jump, for knockback. */
    private static final double JUMP_MARGIN = 1.5;

    /**
     * Moon Gravity's jump boost amplifier for this arena: the strongest one (up to Jump Boost II) whose jump still leaves
     * JUMP_MARGIN blocks below the top of the arena's region; -1 for none (just slow falling).
     */
    public static int moonJumpAmplifier(final Arena arena) {
        final double room = arena.headroom() - JUMP_MARGIN;
        int amplifier = -1;
        for (int level = 1; level <= 2; level++)
            if (JUMP_HEIGHTS[level] <= room)
                amplifier = level - 1;
        return amplifier;
    }

    /** Sets a fighter up for this variant (on their thread, after their own things are saved). */
    public void equip(final Player player, final Arena arena) {
        switch (this) {
            case CLASSIC, ICE, SHRINK -> player.getInventory().addItem(SlapFish.create());
            case MOON -> {
                player.getInventory().addItem(SlapFish.create());
                // Long enough for any match; taken off with everything else when their own things are given back
                // As high as the arena has room for (a jump out of the top of it is a setback, which anticheats flag)
                final int jump = moonJumpAmplifier(arena);
                if (jump >= 0)
                    player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 60 * 20, jump, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60 * 20, 0, false, false, true));
            }
            case ARCHER -> {
                final ItemStack bow = new ItemStack(Material.BOW);
                enchant(bow, "punch", 1);
                enchant(bow, "infinity", 1);
                bow.editMeta(meta -> meta.setUnbreakable(true));
                player.getInventory().addItem(SlapFish.tag(bow), SlapFish.tag(new ItemStack(Material.ARROW)));
            }
            case SNOWBALL -> player.getInventory().addItem(SlapFish.tag(new ItemStack(Material.SNOWBALL, 16))); // never runs out
            case ROD -> {
                final ItemStack rod = new ItemStack(Material.FISHING_ROD);
                enchant(rod, "knockback", 1);
                rod.editMeta(meta -> meta.setUnbreakable(true));
                player.getInventory().addItem(SlapFish.tag(rod));
            }
        }
    }

    private static void enchant(final ItemStack item, final String key, final int level) {
        final Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (e != null)
            item.addUnsafeEnchantment(e, level);
    }

    /** "Sumo: Archer (Punch bow)" for the start of the match. */
    public String announce(final Arena arena) {
        // No room for a jump boost there: just the slow fall
        final String hint = this == MOON && moonJumpAmplifier(arena) < 0 ? "fall slow" : this.hint;
        return "<gold>Sumo: <yellow><bold>" + this.title + "</bold></yellow> <gray>(" + hint + ")";
    }
}
