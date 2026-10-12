package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Duel requests: shift-right-click a player near an arena to challenge them there.
 * Duels never count towards stats or pay rewards (see Fight#isDuel).
 */
public final class DuelManager {
    private static final long RESEND_COOLDOWN_MILLIS = 10_000;
    private static final NamespacedKey TOGGLE_KEY = new NamespacedKey(SpawnArena.INSTANCE, "duel_requests_off");

    private record Request(UUID requester, String arena, long expiresAt) {}

    /** target -> (requester -> request) */
    private static final Map<UUID, Map<UUID, Request>> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_SENT = new ConcurrentHashMap<>();

    private DuelManager() {}

    public static boolean acceptsRequests(final Player player) {
        return !player.getPersistentDataContainer().has(TOGGLE_KEY, PersistentDataType.BYTE);
    }

    /** Flips whether the player receives duel requests; returns the new state (true = receiving). */
    public static boolean toggleRequests(final Player player) {
        if (acceptsRequests(player)) {
            player.getPersistentDataContainer().set(TOGGLE_KEY, PersistentDataType.BYTE, (byte) 1);
            REQUESTS.remove(player.getUniqueId());
            return false;
        }
        player.getPersistentDataContainer().remove(TOGGLE_KEY);
        return true;
    }

    /** The closest arena whose region is within duelRadius of this location, or null. */
    public static Arena arenaNear(final Location loc) {
        if (SpawnArena.arenas == null)
            return null;
        final double radius = SpawnArena.settings == null ? 10 : SpawnArena.settings.duelRadius;
        Arena best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Arena arena : SpawnArena.arenas) {
            if (!arena.isSetUp())
                continue;
            final double distance = arena.distanceFrom(loc);
            if (distance <= radius && distance < bestDistance) {
                best = arena;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static void sendRequest(final Player requester, final Player target, final Arena arena) {
        final String problem = cantDuel(requester, arena);
        if (problem != null) {
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + problem));
            return;
        }
        if (cantDuel(target, arena) != null) {
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<red><target> can't duel right now.",
                    Placeholder.unparsed("target", target.getName())));
            return;
        }
        if (!acceptsRequests(target)) {
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<red><target> isn't accepting duel requests.",
                    Placeholder.unparsed("target", target.getName())));
            return;
        }

        final String pair = requester.getUniqueId() + ">" + target.getUniqueId();
        final long now = System.currentTimeMillis();
        final Long last = LAST_SENT.get(pair);
        if (last != null && now - last < RESEND_COOLDOWN_MILLIS) {
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<red>You just challenged <target>. Wait a moment before asking again.",
                    Placeholder.unparsed("target", target.getName())));
            return;
        }
        LAST_SENT.put(pair, now);

        final int seconds = SpawnArena.settings == null ? 30 : SpawnArena.settings.duelRequestSeconds;
        REQUESTS.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(requester.getUniqueId(), new Request(requester.getUniqueId(), arena.getArenaName(), now + seconds * 1000L));

        final String arenaName = Arena.displayName(arena.getArenaName());
        // Placeholders aren't resolved inside click arguments, so the (tag-safe) player name goes in directly
        final String name = requester.getName();
        target.sendMessage(MiniMessage.miniMessage().deserialize(
                "<red><bold>NO STATS OR PAYOUTS</bold></red> <dark_gray>·</dark_gray> <gold><requester></gold> <yellow>challenged you to a duel in </yellow><gold><arena></gold><yellow>!</yellow> "
                        + "<click:run_command:'/arenaduel accept " + name + "'><hover:show_text:'<green>Accept the duel'><green><bold>[Accept]</bold></green></hover></click> "
                        + "<click:run_command:'/arenaduel deny " + name + "'><hover:show_text:'<red>Deny the duel'><red><bold>[Deny]</bold></red></hover></click>",
                Placeholder.unparsed("requester", requester.getName()),
                Placeholder.unparsed("arena", arenaName)));
        requester.sendMessage(MiniMessage.miniMessage().deserialize(
                "<red><bold>NO STATS OR PAYOUTS</bold></red> <dark_gray>·</dark_gray> <yellow>Duel request sent to </yellow><gold><target></gold><yellow> in </yellow><gold><arena></gold><yellow>. It expires in <seconds>s.</yellow>",
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("arena", arenaName),
                Placeholder.unparsed("seconds", String.valueOf(seconds))));
    }

    public static void accept(final Player target, final Player requester) {
        final Request request = take(target, requester);
        if (request == null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize("<red>You don't have a duel request from <requester> (it may have expired).",
                    Placeholder.unparsed("requester", requester.getName())));
            return;
        }

        final Arena arena = SpawnArena.arenas.of(request.arena());
        if (arena == null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize("<red>That arena no longer exists."));
            return;
        }
        for (final Player p : new Player[]{target, requester}) {
            final String problem = cantDuel(p, arena);
            if (problem != null) {
                target.sendMessage(MiniMessage.miniMessage().deserialize(p == target ? "<red>" + problem : "<red><who> can't duel right now.",
                        Placeholder.unparsed("who", p.getName())));
                return;
            }
        }

        // Accepting a duel takes both players out of any queue
        for (final Player p : new Player[]{target, requester}) {
            final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(p);
            if (queue != null)
                queue.removePlayer(p);
        }
        REQUESTS.remove(requester.getUniqueId());

        final String arenaName = Arena.displayName(arena.getArenaName());
        for (final Player p : new Player[]{target, requester}) {
            p.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red><bold>NO STATS OR PAYOUTS</bold></red> <dark_gray>·</dark_gray> <green>Duel accepted! Fighting in </green><gold><arena></gold><green>.</green>",
                    Placeholder.unparsed("arena", arenaName)));
        }
        new Fight().asDuel().initiateFight(requester, target, arena);
    }

    public static void deny(final Player target, final Player requester) {
        if (take(target, requester) == null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize("<red>You don't have a duel request from <requester>.",
                    Placeholder.unparsed("requester", requester.getName())));
            return;
        }
        target.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Denied <requester>'s duel request.",
                Placeholder.unparsed("requester", requester.getName())));
        requester.sendMessage(MiniMessage.miniMessage().deserialize("<red><target> denied your duel request.",
                Placeholder.unparsed("target", target.getName())));
    }

    public static void clear(final Player player) {
        REQUESTS.remove(player.getUniqueId());
        REQUESTS.values().forEach(requests -> requests.remove(player.getUniqueId()));
    }

    private static Request take(final Player target, final Player requester) {
        final Map<UUID, Request> requests = REQUESTS.get(target.getUniqueId());
        if (requests == null)
            return null;
        final Request request = requests.remove(requester.getUniqueId());
        if (request == null || System.currentTimeMillis() > request.expiresAt())
            return null;
        return request;
    }

    /** Why this player can't start a duel in this arena right now, or null if they can. */
    private static String cantDuel(final Player player, final Arena arena) {
        if (SpawnArena.settings != null && !SpawnArena.settings.arenasOpen)
            return "The arenas are closed right now.";
        if (!player.getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return "You must be at spawn to duel.";
        if (SpawnArena.arenas.hasFighter(player))
            return "You're already in a fight.";
        if (arena.getArenaMode() == ArenaMode.SINGLE && !arena.getFights().isEmpty())
            return "There's already a fight in that arena.";
        return null;
    }
}
