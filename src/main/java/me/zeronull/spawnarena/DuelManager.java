package me.zeronull.spawnarena;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.ChatColor;
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
            if (!arena.isSetUp() || arena.isFfa())
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
        final String problem = cantDuel(requester, arena, true);
        if (problem != null) {
            requester.sendMessage(ChatColor.RED + problem);
            return;
        }
        final String targetProblem = cantDuel(target, arena, false);
        if (targetProblem != null) {
            requester.sendMessage(ChatColor.RED + targetProblem);
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
        LAST_SENT.values().removeIf(sent -> now - sent >= RESEND_COOLDOWN_MILLIS); // keep the map from growing forever
        LAST_SENT.put(pair, now);

        final int seconds = SpawnArena.settings == null ? 60 : SpawnArena.settings.duelRequestSeconds;
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
                "<red><bold>NO STATS OR PAYOUTS</bold></red> <dark_gray>·</dark_gray> <yellow>Duel request sent to </yellow><gold><target></gold><yellow> in </yellow><gold><arena></gold><yellow>. It expires in </yellow><gold><seconds>s</gold><yellow>.</yellow> "
                        + "<click:run_command:'/arenaduel cancel " + target.getName() + "'><hover:show_text:'<red>Take back the duel request'><red><bold>[Cancel]</bold></red></hover></click>",
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
            final String problem = cantDuel(p, arena, p == target);
            if (problem != null) {
                target.sendMessage(ChatColor.RED + problem);
                return;
            }
        }

        // Accepting a duel takes both players out of any queue, and drops every other request they had
        for (final Player p : new Player[]{target, requester}) {
            final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(p);
            if (queue != null)
                queue.removePlayer(p);
            clear(p);
        }

        final String arenaName = Arena.displayName(arena.getArenaName());
        for (final Player p : new Player[]{target, requester}) {
            p.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red><bold>NO STATS OR PAYOUTS</bold></red> <dark_gray>·</dark_gray> <green>Duel accepted! Fighting in </green><gold><arena></gold><green>.</green>",
                    Placeholder.unparsed("arena", arenaName)));
        }
        final Arena where = arena.arenaForNewFight(); // a free copy, for a MULTI crystal arena
        if (where == null) {
            target.sendMessage(ChatColor.RED + "Every copy of that arena has a match going on. Try again in a moment.");
            return;
        }
        new Fight().asDuel().initiateFight(requester, target, where);
    }

    public static void deny(final Player target, final Player requester) {
        if (take(target, requester) == null) {
            target.sendMessage(MiniMessage.miniMessage().deserialize("<red>You don't have a duel request from <requester>.",
                    Placeholder.unparsed("requester", requester.getName())));
            return;
        }
        target.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Denied <requester> duel request.",
                Placeholder.unparsed("requester", Grammar.possessive(requester.getName()))));
        requester.sendMessage(MiniMessage.miniMessage().deserialize("<red><target> denied your duel request.",
                Placeholder.unparsed("target", target.getName())));
    }

    /** The requester takes back the request they sent this player ([Cancel] on the "request sent" message). */
    public static void cancel(final Player requester, final Player target) {
        final Map<UUID, Request> requests = REQUESTS.get(target.getUniqueId());
        final Request request = requests == null ? null : requests.remove(requester.getUniqueId());
        if (request == null || System.currentTimeMillis() > request.expiresAt()) {
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<red>You don't have a duel request out to <target> (it may have expired or been answered).",
                    Placeholder.unparsed("target", target.getName())));
            return;
        }
        requester.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Cancelled your duel request to <target>.",
                Placeholder.unparsed("target", target.getName())));
        target.sendMessage(MiniMessage.miniMessage().deserialize("<gray><requester> withdrew their duel request.",
                Placeholder.unparsed("requester", requester.getName())));
    }

    /**
     * Withdraws the duel requests this player has sent (e.g. they joined a queue, where they can't duel),
     * telling them and each player they'd challenged.
     */
    public static void cancelSentRequests(final Player requester, final String because) {
        for (final Map.Entry<UUID, Map<UUID, Request>> entry : REQUESTS.entrySet()) {
            if (entry.getValue().remove(requester.getUniqueId()) == null)
                continue;
            final Player target = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (target == null)
                continue;
            requester.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Your duel request to <target> was cancelled because you <because>.",
                    Placeholder.unparsed("target", target.getName()), Placeholder.unparsed("because", because)));
            target.sendMessage(MiniMessage.miniMessage().deserialize("<gray><requester> withdrew their duel request.",
                    Placeholder.unparsed("requester", requester.getName())));
        }
    }

    /** Removes every request this player sent or received; returns whether there were any. */
    public static boolean clear(final Player player) {
        boolean had = REQUESTS.remove(player.getUniqueId()) != null;
        for (final Map<UUID, Request> requests : REQUESTS.values())
            had |= requests.remove(player.getUniqueId()) != null;
        return had;
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

    /**
     * Why this player can't duel in this arena right now, or null if they can. Phrased for them
     * ({@code self}) or about them, for the other player.
     */
    private static String cantDuel(final Player player, final Arena arena, final boolean self) {
        final String name = player.getName();
        if (SpawnArena.settings != null && !SpawnArena.settings.arenasOpen)
            return "The arenas are closed right now.";
        if (!player.getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return self ? "You must be at spawn to duel." : name + " isn't at spawn.";
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return self ? "You can't duel while in spectator mode." : name + " is in spectator mode.";
        if (SpawnArena.arenas.hasFighter(player)) {
            final Arena current = SpawnArena.arenas.of(player);
            final Fight fight = current == null ? null : current.getFight(player);
            if (fight != null && fight.getState() == FightState.COUNTDOWN)
                return self ? "You're about to start a match." : name + " is about to start a match.";
            return self ? "You're already in a fight." : name + " is in a fight right now.";
        }
        final ArenaQueue queue = ArenaQueue.PLAYER_QUEUE_MAP.get(player);
        if (queue != null && queue.playerInQueue(player))
            return self ? "You're waiting in a queue. Leave it first with /joinarenaqueue " + queue.getArena().getArenaName() + "."
                    : name + " is waiting in a queue.";
        // Same rule as the queue: stay near the arena
        final double maxDistance = SpawnArena.settings == null ? 0 : SpawnArena.settings.queueMaxDistance;
        if (maxDistance > 0 && arena.distanceFrom(player.getLocation()) > maxDistance)
            return self ? "You're too far from the arena." : name + " is too far from the arena.";
        if (arena.isFfa())
            return "That arena is a free-for-all, so there are no duels there.";
        if (arena.getArenaMode() == ArenaMode.SINGLE && !arena.getFights().isEmpty())
            return "There's already a fight in that arena.";
        if (arena.isResetting())
            return "That arena is being reset. Try again in a moment.";
        if (arena.arenaForNewFight() == null)
            return "Every copy of that arena has a match going on. Try again in a moment.";
        return null;
    }
}
