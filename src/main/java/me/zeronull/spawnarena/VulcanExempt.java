package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Vulcan's side of {@link AnticheatExempt}: for a player in an Ice Rink (MULTI) or Moon Gravity match, Vulcan's
 * movement flags, setbacks and punishments are cancelled (its flag events say which category a check is in), so the
 * slide and the high jumps don't flag them. Combat and every other check keep working. Nothing is saved or sent
 * anywhere: a player who leaves the match, the server or the plugin is simply checked again. Linked to Vulcan by
 * reflection, so it's only touched when Vulcan is there.
 */
final class VulcanExempt {
    private static final String[] EVENTS = {"VulcanFlagEvent", "VulcanSetbackEvent", "VulcanPunishEvent"};
    private static final Set<UUID> EXEMPT = ConcurrentHashMap.newKeySet();
    private static volatile boolean hooked;
    private static boolean broken;

    private VulcanExempt() {}

    static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("Vulcan");
    }

    /** Movement checks off for them until {@link #remove}. */
    static void add(final Player player) {
        if (!present() || !hook())
            return;
        EXEMPT.add(player.getUniqueId());
    }

    static void remove(final UUID uuid) {
        EXEMPT.remove(uuid);
    }

    static boolean isExempt(final UUID uuid) {
        return EXEMPT.contains(uuid);
    }

    /** Listens to Vulcan's events (the first time it's needed, so the order plugins load in doesn't matter). */
    private static synchronized boolean hook() {
        if (hooked)
            return true;
        if (broken)
            return false;
        try {
            final ClassLoader loader = Bukkit.getPluginManager().getPlugin("Vulcan").getClass().getClassLoader();
            final Listener owner = new Listener() {};
            final Method getCategory = Class.forName("me.frep.vulcan.api.check.Check", true, loader).getMethod("getCategory");
            for (final String name : EVENTS) {
                @SuppressWarnings("unchecked")
                final Class<? extends Event> type = (Class<? extends Event>) Class.forName("me.frep.vulcan.api.event." + name, true, loader);
                final Method getPlayer = type.getMethod("getPlayer");
                final Method getCheck = type.getMethod("getCheck");
                Bukkit.getPluginManager().registerEvent(type, owner, EventPriority.HIGHEST, (listener, event) -> {
                    if (!type.isInstance(event) || EXEMPT.isEmpty())
                        return;
                    try {
                        final Object player = getPlayer.invoke(event);
                        if (!(player instanceof Player p) || !EXEMPT.contains(p.getUniqueId()))
                            return;
                        final Object check = getCheck.invoke(event);
                        final Object category = check == null ? null : getCategory.invoke(check);
                        if (category != null && category.toString().toLowerCase(Locale.ROOT).contains("movement"))
                            ((Cancellable) event).setCancelled(true);
                    } catch (final ReflectiveOperationException | RuntimeException ignored) {
                        // a Vulcan update changed the event: they're checked as normal
                    }
                }, SpawnArena.INSTANCE, false);
            }
            hooked = true;
            SpawnArena.INSTANCE.getLogger().info("Hooked into Vulcan: Ice Rink and Moon Gravity players aren't flagged for movement.");
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't hook into Vulcan's events; Ice Rink and Moon Gravity "
                    + "players may be flagged by it", e);
            return false;
        }
    }
}
