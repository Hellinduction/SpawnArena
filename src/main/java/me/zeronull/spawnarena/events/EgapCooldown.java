package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.Fight;
import me.zeronull.spawnarena.FightState;
import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An arena's egapCooldownSeconds. Eating an enchanted golden apple while fighting there puts it on the game's own
 * item cooldown (shown on the item and enforced by the game). Only cooldowns started here are ever cleared: when the
 * player leaves the fight by any route (Fight#restore), or on their next join if that never happened (they quit,
 * or the server stopped). Golden apples outside arena fights are never touched.
 */
public final class EgapCooldown implements Listener {
    /** Players with a cooldown started by an arena -> the apple it was started for (its cooldown group). */
    private static final Map<UUID, ItemStack> STARTED = new ConcurrentHashMap<>();

    /** An apple can't finish being eaten while the arena's cooldown is running (e.g. one started just before it). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEatTooSoon(final PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != Material.ENCHANTED_GOLDEN_APPLE)
            return;
        final Player player = e.getPlayer();
        // Checked against the apple that started it, so an apple with a different cooldown group can't slip past
        final ItemStack started = STARTED.get(player.getUniqueId());
        if (started == null || fightingIn(player) == null || player.getCooldown(started) <= 0)
            return;
        e.setCancelled(true);
        player.clearActiveItem();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(final PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != Material.ENCHANTED_GOLDEN_APPLE)
            return;
        final Player player = e.getPlayer();
        final Arena arena = fightingIn(player);
        if (arena == null || arena.getEgapCooldownSeconds() <= 0)
            return;
        final ItemStack apple = e.getItem().asOne();
        final int ticks = arena.getEgapCooldownSeconds() * 20;
        STARTED.put(player.getUniqueId(), apple);
        // Straight away, so holding right-click can't roll into the next apple before the cooldown is on
        player.setCooldown(apple, Math.max(ticks, player.getCooldown(apple)));
        // And again next tick: an apple with its own use_cooldown would otherwise overwrite ours as it finishes
        // being eaten. Checked again then, so a fight that ended in between doesn't leave a cooldown behind
        Threads.entityLater(player, () -> {
            if (!player.isOnline() || fightingIn(player) != arena || !STARTED.containsKey(player.getUniqueId()))
                return;
            player.setCooldown(apple, Math.max(ticks, player.getCooldown(apple)));
            if (player.hasActiveItem() && player.getActiveItem().getType() == Material.ENCHANTED_GOLDEN_APPLE)
                player.clearActiveItem(); // already started on the next one
        }, 1);
    }

    /** Joining with an arena cooldown still recorded: they left mid-fight, so it no longer applies. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        if (STARTED.containsKey(e.getPlayer().getUniqueId()) && fightingIn(e.getPlayer()) == null)
            clear(e.getPlayer());
    }

    /** Takes off the cooldown an arena started, if there is one. Safe to call from any thread, any number of times. */
    public static void clear(final Player player) {
        final ItemStack apple = STARTED.remove(player.getUniqueId());
        if (apple != null && player.isOnline())
            Threads.entity(player, () -> player.setCooldown(apple, 0));
    }

    /** The arena they're fighting in right now (still in, not knocked out), or null. */
    private static Arena fightingIn(final Player player) {
        final Arena arena = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(player);
        if (arena == null)
            return null;
        final Fight fight = arena.getFight(player);
        if (fight == null || !fight.isFighter(player))
            return null;
        final FightState state = fight.getState();
        return state == FightState.PRE_FIGHT_START || state == FightState.IN_FIGHT ? arena : null;
    }
}
