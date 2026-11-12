package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.SpawnArena;
import me.zeronull.spawnarena.Threads;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.RegisteredListener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Plain Folia fires no PlayerTeleportEvent. SpawnArena's own teleport handlers (kicking a fighter out who teleports
 * away, not letting a spectator into an arena, a fighter's fakes in the viewing arena, duel requests, recordings...) are
 * given one anyway, just after each teleport: noticed from the teleport the server sends the player (or, if that can't be
 * watched, from their next step starting somewhere else, or a change of world), from where their last step ended to
 * where they are now, on their own thread as the real event would be. Only SpawnArena's listeners get it (other plugins
 * aren't sent events the server never fired). As it comes after the teleport, one that a handler cancels, or sends
 * somewhere else, has the player moved back there straight away.
 *
 * <p>Off on Paper, with foliaPatchedTeleportEvent (a Folia fork that fires the events), and for any teleport the server
 * did fire an event for (so a fork that fires them for some is still covered for the rest). A respawn after a real death
 * isn't a teleport (Paper doesn't fire one for it either).
 */
public final class FoliaTeleports implements Listener {
    /** Where each player's last step ended (or where a teleport took them). */
    private static final Map<UUID, Location> LAST = new ConcurrentHashMap<>();
    /** A teleport SpawnArena itself is making (cause PLUGIN), where to, until when. */
    private record Expected(Location to, long until) {}
    private static final Map<UUID, Expected> EXPECTED = new ConcurrentHashMap<>();
    /** When each player's ender pearl last landed (the teleport just after is ENDER_PEARL). */
    private static final Map<UUID, Long> PEARLED = new ConcurrentHashMap<>();
    /** A real teleport event the server fired: where to, when (the same teleport isn't given a second one). */
    private static final Map<UUID, Expected> FIRED = new ConcurrentHashMap<>();
    /** Died for real: their next teleport is the respawn. */
    private static final Set<UUID> DIED = ConcurrentHashMap.newKeySet();
    private static final long WINDOW_MILLIS = 2_000;
    private static volatile boolean toldFired;

    /** Whether teleports are noticed this way here (plain Folia, unless foliaPatchedTeleportEvent says otherwise). */
    public static boolean active() {
        return Threads.FOLIA && (SpawnArena.settings == null || !SpawnArena.settings.foliaPatchedTeleportEvent);
    }

    /** On starting: where everyone already online is. */
    public static void start() {
        if (!active())
            return;
        for (final Player player : Bukkit.getOnlinePlayers())
            Threads.entity(player, () -> LAST.put(player.getUniqueId(), player.getLocation()));
    }

    /** SpawnArena is about to teleport this player there itself (Threads.teleport): that one's cause is PLUGIN. */
    public static void expect(final Player player, final Location to) {
        if (active() && to != null)
            EXPECTED.put(player.getUniqueId(), new Expected(to.clone(), System.currentTimeMillis() + WINDOW_MILLIS));
    }

    /**
     * On the player's connection's thread, as the server sends them a teleport: where they were is noted now (before any
     * step of theirs from the new place can come in), and the rest is done on their own thread.
     */
    public static void teleportSent(final Player player) {
        if (!active())
            return;
        final Location from = LAST.get(player.getUniqueId());
        try {
            player.getScheduler().run(SpawnArena.INSTANCE, task -> noticed(player, from), null);
        } catch (final RuntimeException ignored) {
            // SpawnArena is being disabled
        }
    }

    // ── Keeping track ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        if (active())
            LAST.put(e.getPlayer().getUniqueId(), e.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent e) {
        final UUID id = e.getPlayer().getUniqueId();
        LAST.remove(id);
        EXPECTED.remove(id);
        PEARLED.remove(id);
        FIRED.remove(id);
        DIED.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(final PlayerMoveEvent e) {
        if (!active())
            return;
        final Player player = e.getPlayer();
        // Without the connection watched: a step starting well away from where the last ended means a teleport between
        if (!me.zeronull.spawnarena.MatchSounds.watchingPackets()) {
            final Location last = LAST.get(player.getUniqueId());
            if (last != null && (!last.getWorld().equals(e.getFrom().getWorld()) || last.distanceSquared(e.getFrom()) > 8 * 8))
                noticed(player, last, e.getFrom());
        }
        LAST.put(player.getUniqueId(), (e.isCancelled() ? e.getFrom() : e.getTo()).clone());
    }

    /** Without the connection watched: into another world is a teleport too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final PlayerChangedWorldEvent e) {
        if (active() && !me.zeronull.spawnarena.MatchSounds.watchingPackets())
            noticed(e.getPlayer(), LAST.get(e.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearl(final ProjectileHitEvent e) {
        if (active() && e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player shooter)
            PEARLED.put(shooter.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent e) {
        if (active() && !e.isCancelled())
            DIED.add(e.getPlayer().getUniqueId());
    }

    /** A teleport event the server did fire (a fork that fires them): that teleport isn't given another. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFired(final PlayerTeleportEvent e) {
        if (!active() || e.getTo() == null || DISPATCHING.get() == e)
            return;
        FIRED.put(e.getPlayer().getUniqueId(), new Expected(e.getTo().clone(), System.currentTimeMillis() + WINDOW_MILLIS));
        if (!toldFired) {
            toldFired = true;
            SpawnArena.INSTANCE.getLogger().info("This server fires teleport events: SpawnArena uses them (and notices any it doesn't fire)."
                    + " Set foliaPatchedTeleportEvent: true in settings.yml if it fires them for every teleport.");
        }
    }

    // ── A teleport noticed ──────────────────────────────────────────────────

    private static final ThreadLocal<PlayerTeleportEvent> DISPATCHING = new ThreadLocal<>();

    /** On the player's thread, just after a teleport: from {@code from} to where they are now. */
    private static void noticed(final Player player, final Location from) {
        if (player.isOnline())
            noticed(player, from, player.getLocation());
    }

    private static void noticed(final Player player, final Location from, final Location to) {
        final UUID id = player.getUniqueId();
        LAST.put(id, to.clone());
        if (from == null || from.getWorld() == null || to.getWorld() == null)
            return;
        final boolean otherWorld = !from.getWorld().equals(to.getWorld());
        if (!otherWorld && from.distanceSquared(to) < 1.0E-4)
            return; // only turned (or put back where they were)
        if (DIED.remove(id))
            return; // their respawn
        final long now = System.currentTimeMillis();
        final Expected fired = FIRED.remove(id);
        if (fired != null && now <= fired.until() && near(fired.to(), to))
            return; // the server fired the event for this one
        final Expected expected = EXPECTED.remove(id);
        final Long pearled = PEARLED.remove(id);
        final TeleportCause cause = expected != null && now <= expected.until() && near(expected.to(), to) ? TeleportCause.PLUGIN
                : pearled != null && now - pearled <= WINDOW_MILLIS ? TeleportCause.ENDER_PEARL
                : TeleportCause.UNKNOWN;
        dispatch(player, from, to, cause);
    }

    private static boolean near(final Location a, final Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= 2 * 2;
    }

    /** SpawnArena's own teleport handlers, in order, as for a real event; then whatever they decided is done. */
    private static void dispatch(final Player player, final Location from, final Location to, final TeleportCause cause) {
        final PlayerTeleportEvent event = new PlayerTeleportEvent(player, from.clone(), to.clone(), cause);
        DISPATCHING.set(event);
        try {
            for (final RegisteredListener listener : PlayerTeleportEvent.getHandlerList().getRegisteredListeners()) {
                if (listener.getPlugin() != SpawnArena.INSTANCE || listener.getListener() instanceof FoliaTeleports)
                    continue;
                try {
                    listener.callEvent(event);
                } catch (final EventException | RuntimeException e) {
                    SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "A teleport handler failed for " + player.getName(), e);
                }
            }
        } finally {
            DISPATCHING.remove();
        }
        // It's happened already: what a handler stopped is undone, what it sent elsewhere goes there
        if (event.isCancelled())
            Threads.teleport(player, from);
        else if (event.getTo() != null && !near(event.getTo(), to))
            Threads.teleport(player, event.getTo());
    }
}
