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
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
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

    /** Sets a fighter up for this variant (on their thread, after their own things are saved). */
    public void equip(final Player player) {
        switch (this) {
            case CLASSIC, ICE, SHRINK -> player.getInventory().addItem(SlapFish.create());
            case MOON -> {
                player.getInventory().addItem(SlapFish.create());
                // Long enough for any match; taken off with everything else when their own things are given back
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 60 * 20, 1, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60 * 20, 0, false, false, true));
            }
            case ARCHER -> {
                final ItemStack bow = new ItemStack(Material.BOW);
                enchant(bow, "punch", 1);
                enchant(bow, "infinity", 1);
                bow.editMeta(meta -> meta.setUnbreakable(true));
                player.getInventory().addItem(SlapFish.tag(bow), SlapFish.tag(new ItemStack(Material.ARROW)));
            }
            case SNOWBALL -> player.getInventory().addItem(SlapFish.tag(new ItemStack(Material.SNOWBALL, 16)),
                    SlapFish.tag(new ItemStack(Material.SNOWBALL, 16)), SlapFish.tag(new ItemStack(Material.SNOWBALL, 16)));
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
    public String announce() {
        return "<gold>Sumo: <yellow><bold>" + this.title + "</bold></yellow> <gray>(" + this.hint + ")";
    }
}
