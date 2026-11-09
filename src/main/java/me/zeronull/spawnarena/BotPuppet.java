package me.zeronull.spawnarena;

import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a sumo bot looks like to its opponent and anyone watching: a player, as packets only, that follows the bot's
 * (hidden) Mannequin every tick. A Mannequin's arm never moves on the client (only a player's swing animates), so the
 * bot is drawn as a player:
 * its borrowed skin, what it holds, its crouch, its swings and its hurt flash. Hits on it are passed on to the
 * Mannequin by {@link SumoBotListener}. Its own name tag is hidden (with it, the line a below-name scoreboard puts
 * under every player, which a bot has no score for, so it would be blank): "Bot (Medium)" is a text display above it.
 */
final class BotPuppet {
    private static boolean broken;
    static Constructor<?> addPacket, dataPacket, syncPacket, removePacket, equipmentPacket, hurtPacket, animatePacket, headPacket,
            infoPacket, infoEntry, infoRemovePacket, teamCtor, scoreboardCtor;
    static Method getHandle, packAll, getEntityData, positionOf, asNmsCopy, pairOf, sendPacket, dataId, authlibCopy,
            asVanilla, teamPacket, teamRemovePacket, setSuffix, setColour, teamPlayers, formattingByName, setNameTagVisibility, dataValue;
    static Constructor<?> positionCtor, vec3Ctor;
    private static Object textDisplayType, hiddenTag, shownTag, textData, billboardData, interpolationData;
    static Field animateId, headId;
    private static Constructor<?> bundlePacket;
    private static Class<?> addClass, dataClass, removeClass, bundleClass;
    private static Method addGetId, addGetType, addX, addY, addZ, addXRot, addYRot, addHeadRot, addMovement, dataGetId, dataItems,
            removeIds, bundleItems;
    private static Object mannequinType;
    private static Class<?> packetClass;
    static Object playerType, zero, addPlayer, survival;
    static Object[] slots;
    /** The highest entity data id a player and a Mannequin share (Avatar's); the Mannequin's own ones would break a player. */
    private static int lastShared;

    /** How far above its feet its name is shown */
    private static final double LABEL_Y = 2.1;
    /** How far away people watching are drawn it (about as far as players are drawn) */
    private static final double VIEW_DISTANCE = 64;
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
    private static final String[] NMS_SLOTS = {"MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD"};
    /** Fake entity id -> its bot, so a hit on it reaches the bot. */
    private static final Map<Integer, SumoBot> BY_ID = new ConcurrentHashMap<>();
    /** Every puppet drawn now */
    private static final java.util.Set<BotPuppet> SHOWN = ConcurrentHashMap.newKeySet();
    /** A bot's body (its entity id) -> its puppet: watchers' games are told the body is a player (see forWatcher) */
    private static final Map<Integer, BotPuppet> BODIES = new ConcurrentHashMap<>();
    /** For names and team names, one per puppet. */
    private static final java.util.concurrent.atomic.AtomicInteger TEAMS = new java.util.concurrent.atomic.AtomicInteger();

    final int id = Bukkit.getUnsafe().nextEntityId();
    /** Its name, a text display above its head */
    private final int labelId = Bukkit.getUnsafe().nextEntityId();
    private boolean labelLow;
    private final UUID uuid;
    private final Player opponent;
    private final SumoBot bot;
    private final Object gameProfile;
    /** Its name tag team and name, as its opponent sees them (outlined as the other side) and as anyone else does */
    private final Object opponentTeam, othersTeam;
    private final Object opponentLabel, othersLabel;
    /** Who it's drawn for now (as a player, by packets): only its opponent */
    private final java.util.Set<Player> viewers = ConcurrentHashMap.newKeySet();
    /**
     * Everyone else watching (not in another fight in this arena) is shown its body itself, a real entity the server
     * keeps moving and takes away like any other (the packet-only player could be left standing, or never arrive, in a
     * watcher's game). Their game is told it's a player, though, so its arm swings for them (see forWatcher).
     */
    private final java.util.Set<Player> watchers = ConcurrentHashMap.newKeySet();
    /** The body's outline colour as watchers see it (a team of their own, by packets) */
    private Object watcherTeam;
    /** For outlines some watchers see in a colour of their own (their side's, in a match with players and bots) */
    private String teamName, name, bodyUuid;
    private BotDifficulty level;
    /** Colour -> {its name tag team, its body's team, its label}: made the first time a watcher needs it */
    private final Map<net.kyori.adventure.text.format.NamedTextColor, Object[]> byColour = new ConcurrentHashMap<>();
    /** Viewer -> when to send it to them again: {first, second} (the first is 0 once done) */
    private final Map<UUID, long[]> redrawAt = new ConcurrentHashMap<>();
    /** Everyone it's ever been sent to: all told it's gone when it goes, viewers now or not */
    private final java.util.Set<UUID> sentTo = ConcurrentHashMap.newKeySet();
    /** Where each viewer was at the last look (see refreshViewers) */
    private final Map<UUID, org.bukkit.Location> lastAt = new ConcurrentHashMap<>();
    private double x, y, z;
    private float yaw, pitch;
    private int bodyId;
    private final ItemStack[] gear = new ItemStack[GEAR.length];
    private long ticks;
    private volatile boolean shown;

    private BotPuppet(final UUID uuid, final Player opponent, final SumoBot bot, final Object gameProfile, final Object opponentTeam, final Object othersTeam,
                      final Object opponentLabel, final Object othersLabel) {
        this.uuid = uuid;
        this.opponent = opponent;
        this.bot = bot;
        this.gameProfile = gameProfile;
        this.opponentTeam = opponentTeam;
        this.othersTeam = othersTeam;
        this.opponentLabel = opponentLabel;
        this.othersLabel = othersLabel;
    }

    static SumoBot botOf(final int entityId) {
        return BY_ID.get(entityId);
    }

    /**
     * {@code player} was just teleported, respawned or moved world: their game may have thrown away every entity it had
     * (a world change does, and so can a plugin's loading screen on a teleport), bots included, which the server doesn't
     * send again by itself. So the bots they see are sent to them again, a second and three seconds on (once their game
     * has settled); if it kept them, that changes nothing.
     */
    static void redraw(final Player player) {
        final long now = System.currentTimeMillis();
        for (final BotPuppet puppet : SHOWN)
            puppet.redrawAt.put(player.getUniqueId(), new long[]{now + 1000, now + 3000});
    }

    /**
     * {@code fight} has just been set up: its fighters stop seeing the bots of other matches in its arena straight away
     * (who sees a bot is otherwise only looked at twice a second, and a bot match starts the moment it's picked).
     */
    static void hideFrom(final Fight fight) {
        for (final BotPuppet puppet : SHOWN) {
            final SumoBot.BotMatch theirs = puppet.bot.getMatch();
            if (theirs.fight() == fight || theirs.arena() == null || fight.getArena() == null
                    || !theirs.arena().getArenaName().equals(fight.getArena().getArenaName()))
                continue;
            for (final Player p : fight.getMembers()) {
                if (puppet.viewers.remove(p))
                    puppet.despawnFor(p);
                if (puppet.watchers.remove(p))
                    puppet.unwatch(p);
            }
        }
    }

    /**
     * A puppet of {@code body}, drawn for its opponent and whoever's watching; null if it can't be drawn (its opponent
     * is then shown the Mannequin itself). On the bot's thread.
     */
    static BotPuppet show(final Player opponent, final SumoBot bot, final Mannequin body, final com.destroystokyo.paper.profile.PlayerProfile skin,
                          final BotDifficulty level) {
        if (!ready())
            return null;
        try {
            // A name of its own (never shown: its name is the text display), as one name can only be on one team: two
            // bots both named Bot would take each other's team, and the name tag hidden by it, when either one ends
            final int n = TEAMS.incrementAndGet();
            final String name = "Bot_" + Integer.toString(n, 36);
            final UUID uuid = UUID.randomUUID();
            final com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(uuid, name);
            if (skin != null)
                profile.setProperties(skin.getProperties());
            // Outlined as a player in the fight is: the other side's colour to its opponent, the fight's to anyone else
            // (the name takes the outline's colour); with no outlines, named in its level's colour
            // (a show match between bots isn't a fight: each team in its own colour)
            final Fight fight = bot.getMatch().fight();
            final net.kyori.adventure.text.format.NamedTextColor team = bot.getMatch().teamColour(bot);
            final net.kyori.adventure.text.format.NamedTextColor toOpponent = fight == null || team != null ? team : FightGlow.botOutline(fight);
            final net.kyori.adventure.text.format.NamedTextColor toOthers = fight == null || team != null ? team : FightGlow.fightOutline(fight);
            if (toOpponent != null)
                body.setGlowing(true); // passed on with the rest of its entity data
            final String teamName = "sa_bot_" + Integer.toString(n, 36);
            final BotPuppet puppet = new BotPuppet(uuid, opponent, bot, authlibCopy.invoke(null, profile),
                    team(teamName, name, toOpponent, level), team(teamName, name, toOthers, level), label(toOpponent, level), label(toOthers, level));
            puppet.teamName = teamName;
            puppet.name = name;
            puppet.level = level;
            puppet.bodyUuid = body.getUniqueId().toString();
            final org.bukkit.Location at = body.getLocation();
            puppet.x = at.getX();
            puppet.y = at.getY();
            puppet.z = at.getZ();
            puppet.yaw = at.getYaw();
            puppet.pitch = at.getPitch();
            puppet.gearChanged(body);
            // (its own name tag hidden: watchers get the label above it, as its opponent does, which every game shows)
            puppet.watcherTeam = team("sa_botw_" + Integer.toString(n, 36), body.getUniqueId().toString(), toOthers, level, true);
            puppet.shown = true;
            puppet.bodyId = body.getEntityId();
            BODIES.put(puppet.bodyId, puppet);
            BY_ID.put(puppet.id, bot);
            SHOWN.add(puppet);
            puppet.refreshViewers(body);
            return puppet;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            broken = true;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't draw sumo bots as players (" + e + "); they're shown as mannequins.");
            return null;
        }
    }

    private static Object team(final String teamName, final String member, final net.kyori.adventure.text.format.NamedTextColor outline,
                               final BotDifficulty level) throws ReflectiveOperationException {
        return team(teamName, member, outline, level, true);
    }

    private static Object team(final String teamName, final String member, final net.kyori.adventure.text.format.NamedTextColor outline,
                               final BotDifficulty level, final boolean hideName) throws ReflectiveOperationException {
        final Object team = teamCtor.newInstance(scoreboardCtor.newInstance(), teamName);
        final Object colour = formattingByName.invoke(null, outline != null ? outline.toString() : level.colour.replaceAll("[<>]", ""));
        if (colour != null)
            setColour.invoke(team, colour);
        setNameTagVisibility.invoke(team, hideName ? hiddenTag : shownTag);
        @SuppressWarnings("unchecked") final java.util.Collection<String> players = (java.util.Collection<String>) teamPlayers.invoke(team);
        players.add(member);
        return team;
    }

    private static Object label(final net.kyori.adventure.text.format.NamedTextColor outline, final BotDifficulty level) throws ReflectiveOperationException {
        return asVanilla.invoke(null, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                (outline != null ? "<" + outline + ">" : level.colour) + "Bot <gray>(" + level.coloured() + "<gray>)"));
    }

    /**
     * Who should see it: its opponent, and anyone near enough to watch, except those in another fight in the same
     * arena (fighters of different fights there are hidden from each other, and so is this). Drawn for anyone new,
     * taken away from anyone who no longer should.
     */
    private void refreshViewers(final Mannequin body) throws ReflectiveOperationException {
        final java.util.Set<Player> should = new java.util.HashSet<>();
        if (this.opponent != null && this.opponent.isOnline() && this.opponent.getWorld().equals(body.getWorld()))
            should.add(this.opponent);
        final org.bukkit.Location bodyAt = body.getLocation();
        for (final Player p : body.getWorld().getNearbyPlayers(bodyAt, VIEW_DISTANCE))
            if (!p.equals(this.opponent) && !this.inOtherMatchHere(p) && withinView(p, bodyAt))
                should.add(p);
        // Watchers: shown its body (see watchers)
        final java.util.Set<Player> watch = new java.util.HashSet<>(should);
        if (this.opponent != null)
            watch.remove(this.opponent);
        should.retainAll(this.opponent == null ? java.util.Set.of() : java.util.Set.of(this.opponent));
        for (final java.util.Iterator<Player> it = this.watchers.iterator(); it.hasNext(); ) {
            final Player p = it.next();
            if (!watch.contains(p)) {
                it.remove();
                this.unwatch(p);
            }
        }
        for (final Player p : watch)
            if (this.watchers.add(p))
                this.watch(p, body);
        // Its opponent: drawn as a player
        for (final java.util.Iterator<Player> it = this.viewers.iterator(); it.hasNext(); ) {
            final Player p = it.next();
            if (!should.contains(p)) {
                it.remove();
                if (p.isOnline() && p.getWorld().equals(body.getWorld()))
                    this.despawnFor(p);
            }
        }
        final long now = System.currentTimeMillis();
        for (final Player p : should)
            if ((p.equals(this.opponent) || !this.inOtherMatchHere(p)) && this.viewers.add(p)) { // (asked again: one may just have started)
                this.spawnFor(p, body);
                // And again a second and three seconds on, in case their game didn't take it the first time
                this.redrawAt.put(p.getUniqueId(), new long[]{now + 1000, now + 3000});
            }
        // Sent again to anyone whose game may have dropped it (see redraw): after a teleport event, or a jump in where
        // they are or which world (some teleports, e.g. Folia's, come with no event)
        for (final Player p : this.viewers) {
            final org.bukkit.Location here = p.getLocation(), was = this.lastAt.put(p.getUniqueId(), here);
            if (was != null && (was.getWorld() != here.getWorld() || was.distanceSquared(here) > 64))
                this.redrawAt.put(p.getUniqueId(), new long[]{now + 1000, now + 3000});
        }
        for (final java.util.Iterator<Map.Entry<UUID, long[]>> it = this.redrawAt.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, long[]> e = it.next();
            final long[] at = e.getValue();
            final int next = at[0] > 0 ? 0 : 1;
            if (now < at[next])
                continue;
            final Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && this.viewers.contains(p)) {
                // Taken off first: their game never ends up with two of it (one left standing when it goes)
                send(p, removePacket.newInstance((Object) new int[]{this.id, this.labelId}));
                this.spawnFor(p, body);
            }
            if (next == 0)
                at[0] = 0;
            else
                it.remove();
        }
    }

    /**
     * Close enough that their game keeps the chunk it's in loaded: past their view distance, their game throws away the
     * entities in chunks it unloads, this one included, and the server wouldn't send it again; taken off before then,
     * it's drawn again when they come back.
     */
    private static boolean withinView(final Player p, final org.bukkit.Location at) {
        final int chunks = Math.min(p.getViewDistance(), p.getClientViewDistance());
        final double blocks = Math.max(16, (chunks - 1) * 16);
        return p.getLocation().distanceSquared(at) <= blocks * blocks;
    }

    /**
     * For /arenabotviewers: each bot drawn now, who it's drawn for, and why anyone else within 100 blocks isn't one
     * (MiniMessage lines, sent to {@code to} from each bot's own thread).
     */
    static int report(final org.bukkit.command.CommandSender to) {
        int n = 0;
        for (final BotPuppet puppet : SHOWN) {
            final Mannequin body = puppet.bot.getBody();
            if (body == null)
                continue;
            n++;
            Threads.entity(body, () -> {
                final org.bukkit.Location at = body.getLocation();
                final List<String> lines = new ArrayList<>();
                lines.add("<aqua>" + puppet.bot.getLevel().label + " bot <gray>(" + (puppet.opponent != null ? "against " + puppet.opponent.getName() : "a bot fight")
                        + ") at <white>" + at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ());
                lines.add("<gray> Drawn for: <white>" + (puppet.viewers.isEmpty() ? "nobody" : String.join(", ", puppet.viewers.stream().map(Player::getName).toList())));
                lines.add("<gray> Shown to (watching): <white>" + (puppet.watchers.isEmpty() ? "nobody" : String.join(", ", puppet.watchers.stream().map(Player::getName).toList())));
                for (final Player p : at.getWorld().getPlayers()) {
                    if (puppet.viewers.contains(p) || puppet.watchers.contains(p) || p.getLocation().distanceSquared(at) > 100 * 100)
                        continue;
                    final double distance = p.getLocation().distance(at);
                    final int chunks = Math.min(p.getViewDistance(), p.getClientViewDistance());
                    final String why;
                    if (distance > VIEW_DISTANCE)
                        why = "further than " + (int) VIEW_DISTANCE + " blocks";
                    else if (puppet.inOtherMatchHere(p))
                        why = "in another match in this arena";
                    else if (!withinView(p, at))
                        why = "past their view distance (server " + p.getViewDistance() + ", their game " + p.getClientViewDistance() + " chunks: "
                                + (int) Math.max(16, (chunks - 1) * 16) + " blocks)";
                    else
                        why = "should be: not checked yet (it looks twice a second)";
                    lines.add("<gray> Not drawn for <white>" + p.getName() + "<gray>, " + String.format("%.1f", distance) + " blocks away: <yellow>" + why);
                }
                for (final String line : lines)
                    to.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line));
            });
        }
        return n;
    }

    /** In another match in this bot's arena: fighters of different matches there don't see each other. */
    private boolean inOtherMatchHere(final Player p) {
        final Fight fight = this.bot.getMatch().fight();
        final Arena arena = this.bot.getMatch().arena();
        final Arena theirs = SpawnArena.arenas == null ? null : SpawnArena.arenas.of(p);
        final Fight theirFight = theirs == null ? null : theirs.getFight(p);
        return theirFight != null && theirFight != fight && arena != null && theirs.getArenaName().equals(arena.getArenaName());
    }

    private void spawnFor(final Player viewer, final Mannequin body) throws ReflectiveOperationException {
        final boolean opp = viewer.equals(this.opponent);
        this.sentTo.add(viewer.getUniqueId());
        send(viewer, infoPacket.newInstance(EnumSet.of((Enum) addPlayer), infoEntry.newInstance(this.uuid, this.gameProfile, false, 0, survival,
                null, true, 0, null)));
        send(viewer, teamPacket.invoke(null, opp ? this.opponentTeam : this.othersTeam, true));
        send(viewer, addPacket.newInstance(this.id, this.uuid, this.x, this.y, this.z, this.pitch, this.yaw, playerType, 0, zero, (double) this.yaw));
        send(viewer, this.data(body));
        send(viewer, this.equipment());
        send(viewer, this.head(body));
        this.sendLabel(viewer, opp ? this.opponentLabel : this.othersLabel);
    }

    /** Shows a watcher its body (on their thread), outlined in the fight's colour. */
    private void watch(final Player p, final Mannequin body) {
        Threads.entity(p, () -> {
            if (!p.isOnline() || !this.shown || !body.isValid())
                return;
            this.sentTo.add(p.getUniqueId());
            // In its own colour for them (e.g. green on their side, red on the other), or as everyone else sees it
            Object nameTeam = this.othersTeam, bodyTeam = this.watcherTeam, label = this.othersLabel;
            try {
                final net.kyori.adventure.text.format.NamedTextColor colour = this.bot.getMatch().colourFor(this.bot, p);
                if (colour != null && this.teamName != null) {
                    final Object[] own = this.byColour.computeIfAbsent(colour, c -> {
                        try {
                            return new Object[]{team(this.teamName, this.name, c, this.level),
                                    team(this.teamName.replace("sa_bot_", "sa_botw_"), this.bodyUuid, c, this.level, true), label(c, this.level)};
                        } catch (final ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    nameTeam = own[0];
                    bodyTeam = own[1];
                    label = own[2];
                }
            } catch (final RuntimeException ignored) {
                // as everyone else sees it
            }
            try {
                // Its team first, so its own name tag is hidden from the start: as a player (see forWatcher), and as
                // the Mannequin, should their game get that
                send(p, teamPacket.invoke(null, nameTeam, true));
                if (bodyTeam != null)
                    send(p, teamPacket.invoke(null, bodyTeam, true));
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // just no colour
            }
            p.showEntity(SpawnArena.INSTANCE, body);
            try {
                this.sendLabel(p, label);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // just no name
            }
        });
    }

    /** Its body hidden from a watcher again (on their thread). */
    private void unwatch(final Player p) {
        final Mannequin body = this.bot.getBody();
        Threads.entity(p, () -> {
            if (!p.isOnline())
                return;
            if (body != null)
                p.hideEntity(SpawnArena.INSTANCE, body);
            try {
                send(p, removePacket.newInstance((Object) new int[]{this.labelId}));
                if (this.watcherTeam != null)
                    send(p, teamRemovePacket.invoke(null, this.watcherTeam));
                send(p, teamRemovePacket.invoke(null, this.othersTeam));
                send(p, infoRemovePacket.newInstance(List.of(this.uuid)));
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // they've left
            }
        });
    }

    /** Its name, a text display above its head, sent to {@code viewer}. */
    private void sendLabel(final Player viewer, final Object text) throws ReflectiveOperationException {
        send(viewer, addPacket.newInstance(this.labelId, UUID.randomUUID(), this.x, this.y + this.labelHeight(), this.z, 0f, 0f,
                textDisplayType, 0, zero, 0d));
        send(viewer, dataPacket.newInstance(this.labelId, List.of(dataValue.invoke(null, textData, text),
                dataValue.invoke(null, billboardData, (byte) 3), dataValue.invoke(null, interpolationData, 2))));
    }

    private void despawnFor(final Player viewer) {
        try {
            send(viewer, removePacket.newInstance((Object) new int[]{this.id, this.labelId}));
            send(viewer, teamRemovePacket.invoke(null, viewer.equals(this.opponent) ? this.opponentTeam : this.othersTeam));
            send(viewer, infoRemovePacket.newInstance(List.of(this.uuid)));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // they've left, or it goes with the chunk
        }
    }

    private void sendAll(final Object packet) throws ReflectiveOperationException {
        for (final Player viewer : this.viewers)
            send(viewer, packet);
    }

    /** Every tick, on the bot's thread: where it is, how it's standing, what it holds; who's watching, now and then. */
    void update(final Mannequin body) {
        if (!this.shown)
            return;
        this.ticks++;
        try {
            if (this.ticks % 10 == 0)
                this.refreshViewers(body);
            final org.bukkit.Location at = body.getLocation();
            if (at.getX() != this.x || at.getY() != this.y || at.getZ() != this.z || at.getYaw() != this.yaw || at.getPitch() != this.pitch) {
                this.x = at.getX();
                this.y = at.getY();
                this.z = at.getZ();
                this.yaw = at.getYaw();
                this.pitch = at.getPitch();
                this.sendAll(syncPacket.newInstance(this.id, positionOf.invoke(null, handle(body)), body.isOnGround()));
                this.sendAll(this.head(body));
                this.moveLabel(body);
            } else if ((body.getPose() == org.bukkit.entity.Pose.SNEAKING) != this.labelLow) {
                this.moveLabel(body);
            }
            if (this.gearChanged(body))
                this.sendAll(this.equipment());
            if (this.ticks % 2 == 0) // crouching, sprinting...
                this.sendAll(this.data(body));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Stopped drawing a sumo bot: " + e);
            this.remove();
        }
    }

    private double labelHeight() {
        return this.labelLow ? LABEL_Y - 0.3 : LABEL_Y;
    }

    /** Above its head, as a name tag is (a little lower while it crouches). */
    private void moveLabel(final Mannequin body) throws ReflectiveOperationException {
        this.labelLow = body.getPose() == org.bukkit.entity.Pose.SNEAKING;
        final Object at = vec3Ctor.newInstance(this.x, this.y + this.labelHeight(), this.z);
        final Object move = syncPacket.newInstance(this.labelId, positionCtor.newInstance(at, zero, 0f, 0f), false);
        this.sendAll(move);
        for (final Player watcher : this.watchers)
            send(watcher, move);
    }

    void swing(final Mannequin body) {
        if (!this.shown)
            return;
        try {
            final Object packet = animatePacket.newInstance(handle(body), 0);
            animateId.setInt(packet, this.id);
            this.sendAll(packet);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no swing
        }
    }

    /** The red flash, from {@code yaw} (degrees, as the hurt animation takes it). */
    void hurt(final float yaw) {
        if (!this.shown)
            return;
        try {
            this.sendAll(hurtPacket.newInstance(this.id, yaw));
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // just no flash
        }
    }

    void remove() {
        if (!this.shown)
            return;
        this.shown = false;
        BODIES.remove(this.bodyId, this);
        BY_ID.remove(this.id);
        SHOWN.remove(this);
        for (final Player viewer : this.viewers)
            this.despawnFor(viewer);
        this.viewers.clear();
        for (final Player watcher : this.watchers)
            this.unwatch(watcher);
        this.watchers.clear();
        // Anyone else it was ever sent to (a watcher who walked off, changed world or relogged meanwhile), and everyone
        // once more a second on, in case anything of it was still on its way to them
        final java.util.Set<UUID> all = new java.util.HashSet<>(this.sentTo);
        this.goneFor(all);
        Threads.globalLater(() -> this.goneFor(all), 20L);
    }

    private void goneFor(final java.util.Set<UUID> ids) {
        for (final UUID id : ids) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null)
                Threads.entity(p, () -> {
                    if (p.isOnline())
                        this.despawnFor(p);
                });
        }
    }

    /**
     * On each player's connection, as a packet is sent (see MatchSounds): a bot's body, a Mannequin, is told to their
     * game as a player instead (its skin, its name tag hidden), as a Mannequin's arm never moves on the client and a
     * player's does. The server still adds it, moves it and takes it away as it would any entity, so it can't be left
     * standing or never arrive. {@code asPlayer}: the entities their game has as players this way; their entity data
     * is cut to what a player has (a Mannequin's own would break a player). On the connection's thread.
     */
    static Object forWatcher(final java.util.Set<Integer> asPlayer, final io.netty.channel.ChannelHandlerContext ctx, final Object msg) {
        if (msg == null || addClass == null || (BODIES.isEmpty() && asPlayer.isEmpty()))
            return msg;
        try {
            if (bundleClass.isInstance(msg)) {
                final List<Object> parts = new ArrayList<>();
                boolean changed = false;
                for (final Object part : (Iterable<?>) bundleItems.invoke(msg)) {
                    final Object now = forWatcherOne(asPlayer, ctx, part);
                    changed |= now != part;
                    if (now != null)
                        parts.add(now);
                }
                return changed ? bundlePacket.newInstance(parts) : msg;
            }
            final Object now = forWatcherOne(asPlayer, ctx, msg);
            return now != null ? now : bundlePacket.newInstance(List.of()); // (dropped: an empty bundle does nothing)
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return msg;
        }
    }

    private static Object forWatcherOne(final java.util.Set<Integer> asPlayer, final io.netty.channel.ChannelHandlerContext ctx, final Object packet)
            throws ReflectiveOperationException {
        if (addClass.isInstance(packet)) {
            final int id = (int) addGetId.invoke(packet);
            final BotPuppet puppet = BODIES.get(id);
            if (puppet == null || addGetType.invoke(packet) != mannequinType || !puppet.shown) {
                asPlayer.remove(id);
                return packet;
            }
            // Who it is first (its skin): their game takes no player it doesn't know of
            final Object info = infoPacket.newInstance(EnumSet.of((Enum) addPlayer), infoEntry.newInstance(puppet.uuid, puppet.gameProfile, false, 0,
                    survival, null, true, 0, null));
            final Object add = addPacket.newInstance(id, puppet.uuid, addX.invoke(packet), addY.invoke(packet), addZ.invoke(packet),
                    addXRot.invoke(packet), addYRot.invoke(packet), playerType, 0, addMovement.invoke(packet), (double) (float) addHeadRot.invoke(packet));
            ctx.write(info);
            asPlayer.add(id);
            return add;
        }
        if (dataClass.isInstance(packet)) {
            final int id = (int) dataGetId.invoke(packet);
            if (!asPlayer.contains(id))
                return packet;
            try {
                final List<?> all = (List<?>) dataItems.invoke(packet);
                final List<Object> shared = new ArrayList<>(all.size());
                for (final Object value : all)
                    if ((int) dataId.invoke(value) <= lastShared)
                        shared.add(value);
                return shared.size() == all.size() ? packet : dataPacket.newInstance(id, shared);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return null; // never let a Mannequin's own data through to it: their game would disconnect
            }
        }
        if (removeClass.isInstance(packet) && !asPlayer.isEmpty())
            for (final Object id : (Iterable<?>) removeIds.invoke(packet))
                asPlayer.remove(id);
        return packet;
    }

    private Object data(final Mannequin body) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") final List<Object> all = (List<Object>) packAll.invoke(getEntityData.invoke(handle(body)));
        final List<Object> shared = new ArrayList<>(all.size());
        for (final Object value : all)
            if ((int) dataId.invoke(value) <= lastShared)
                shared.add(value);
        return dataPacket.newInstance(this.id, shared);
    }

    private Object head(final Mannequin body) throws ReflectiveOperationException {
        final Object packet = headPacket.newInstance(handle(body), (byte) Math.floor(body.getLocation().getYaw() * 256f / 360f));
        headId.setInt(packet, this.id);
        return packet;
    }

    private boolean gearChanged(final Mannequin body) {
        final org.bukkit.inventory.EntityEquipment equipment = body.getEquipment();
        boolean changed = false;
        for (int i = 0; i < GEAR.length; i++) {
            final ItemStack item = equipment.getItem(GEAR[i]);
            if (!Objects.equals(item, this.gear[i])) {
                this.gear[i] = item.clone();
                changed = true;
            }
        }
        return changed;
    }

    private Object equipment() throws ReflectiveOperationException {
        final List<Object> list = new ArrayList<>(GEAR.length);
        for (int i = 0; i < GEAR.length; i++)
            list.add(pairOf.invoke(null, slots[i], asNmsCopy.invoke(null, this.gear[i] == null ? new ItemStack(org.bukkit.Material.AIR) : this.gear[i])));
        return equipmentPacket.newInstance(this.id, list);
    }

    // ── Reflection ──────────────────────────────────────────────────────────

    /** Loads what drawing players by packets needs (shared with {@link ArenaReplay}); false if this server can't. */
    static boolean ready() {
        if (addPacket != null)
            return true;
        if (broken)
            return false;
        try {
            final ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            final Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity", true, cl);
            final Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType", true, cl);
            final Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3", true, cl);
            final Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation", true, cl);
            final Class<?> synched = Class.forName("net.minecraft.network.syncher.SynchedEntityData", true, cl);
            final Class<?> slot = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
            final Class<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard", true, cl);
            final Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam", true, cl);
            final Class<?> component = Class.forName("net.minecraft.network.chat.Component", true, cl);
            final Class<?> formatting = Class.forName("net.minecraft.ChatFormatting", true, cl);
            packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
            final String game = "net.minecraft.network.protocol.game.";
            final Class<?> info = Class.forName(game + "ClientboundPlayerInfoUpdatePacket", true, cl);
            final Class<?> entry = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Entry", true, cl);
            final Class<?> action = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Action", true, cl);
            infoPacket = info.getConstructor(EnumSet.class, entry);
            infoEntry = entry.getDeclaredConstructors()[0];
            addPlayer = action.getField("ADD_PLAYER").get(null);
            survival = Class.forName("net.minecraft.world.level.GameType", true, cl).getField("SURVIVAL").get(null);
            infoRemovePacket = Class.forName(game + "ClientboundPlayerInfoRemovePacket", true, cl).getConstructor(List.class);
            final Class<?> teamPacketClass = Class.forName(game + "ClientboundSetPlayerTeamPacket", true, cl);
            teamPacket = teamPacketClass.getMethod("createAddOrModifyPacket", playerTeam, boolean.class);
            teamRemovePacket = teamPacketClass.getMethod("createRemovePacket", playerTeam);
            scoreboardCtor = scoreboard.getConstructor();
            teamCtor = playerTeam.getConstructor(scoreboard, String.class);
            setSuffix = playerTeam.getMethod("setPlayerSuffix", component);
            setColour = playerTeam.getMethod("setColor", formatting);
            teamPlayers = playerTeam.getMethod("getPlayers");
            formattingByName = formatting.getMethod("getByName", String.class);
            authlibCopy = Class.forName("com.destroystokyo.paper.profile.CraftPlayerProfile", true, cl)
                    .getMethod("asAuthlibCopy", com.destroystokyo.paper.profile.PlayerProfile.class);
            asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure", true, cl).getMethod("asVanilla", net.kyori.adventure.text.Component.class);
            addPacket = Class.forName(game + "ClientboundAddEntityPacket", true, cl).getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            dataPacket = Class.forName(game + "ClientboundSetEntityDataPacket", true, cl).getConstructor(int.class, List.class);
            syncPacket = Class.forName(game + "ClientboundEntityPositionSyncPacket", true, cl).getConstructor(int.class, pmr, boolean.class);
            removePacket = Class.forName(game + "ClientboundRemoveEntitiesPacket", true, cl).getConstructor(int[].class);
            equipmentPacket = Class.forName(game + "ClientboundSetEquipmentPacket", true, cl).getConstructor(int.class, List.class);
            hurtPacket = Class.forName(game + "ClientboundHurtAnimationPacket", true, cl).getConstructor(int.class, float.class);
            final Class<?> animate = Class.forName(game + "ClientboundAnimatePacket", true, cl);
            animatePacket = animate.getConstructor(nmsEntity, int.class);
            animateId = field(animate, "id");
            final Class<?> head = Class.forName(game + "ClientboundRotateHeadPacket", true, cl);
            headPacket = head.getConstructor(nmsEntity, byte.class);
            headId = field(head, "entityId");
            packAll = synched.getMethod("packAll");
            dataId = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, cl).getMethod("id");
            getEntityData = nmsEntity.getMethod("getEntityData");
            positionOf = pmr.getMethod("of", nmsEntity);
            asNmsCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, cl).getMethod("asNMSCopy", ItemStack.class);
            pairOf = Class.forName("com.mojang.datafixers.util.Pair", true, cl).getMethod("of", Object.class, Object.class);
            playerType = entityType.getField("PLAYER").get(null);
            zero = vec3.getField("ZERO").get(null);
            slots = new Object[NMS_SLOTS.length];
            for (int i = 0; i < NMS_SLOTS.length; i++)
                slots[i] = slot.getField(NMS_SLOTS[i]).get(null);
            textDisplayType = entityType.getField("TEXT_DISPLAY").get(null);
            hiddenTag = Class.forName("net.minecraft.world.scores.Team$Visibility", true, cl).getField("NEVER").get(null);
            shownTag = hiddenTag.getClass().getField("ALWAYS").get(null);
            setNameTagVisibility = playerTeam.getMethod("setNameTagVisibility", hiddenTag.getClass());
            final Class<?> display = Class.forName("net.minecraft.world.entity.Display", true, cl);
            textData = field(Class.forName("net.minecraft.world.entity.Display$TextDisplay", true, cl), "DATA_TEXT_ID").get(null);
            billboardData = field(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID").get(null);
            interpolationData = field(display, "DATA_POS_ROT_INTERPOLATION_DURATION_ID").get(null);
            final Class<?> accessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, cl);
            dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, cl).getMethod("create", accessorClass, Object.class);
            positionCtor = pmr.getConstructor(vec3, vec3, float.class, float.class);
            mannequinType = entityType.getField("MANNEQUIN").get(null);
            final Class<?> addC = Class.forName(game + "ClientboundAddEntityPacket", true, cl);
            addGetId = addC.getMethod("getId");
            addGetType = addC.getMethod("getType");
            addX = addC.getMethod("getX");
            addY = addC.getMethod("getY");
            addZ = addC.getMethod("getZ");
            addXRot = addC.getMethod("getXRot");
            addYRot = addC.getMethod("getYRot");
            addHeadRot = addC.getMethod("getYHeadRot");
            addMovement = addC.getMethod("getMovement");
            final Class<?> dataC = Class.forName(game + "ClientboundSetEntityDataPacket", true, cl);
            dataGetId = dataC.getMethod("id");
            dataItems = dataC.getMethod("packedItems");
            final Class<?> removeC = Class.forName(game + "ClientboundRemoveEntitiesPacket", true, cl);
            removeIds = removeC.getMethod("getEntityIds");
            final Class<?> bundleC = Class.forName(game + "ClientboundBundlePacket", true, cl);
            bundlePacket = bundleC.getConstructor(Iterable.class);
            bundleItems = Class.forName("net.minecraft.network.protocol.BundlePacket", true, cl).getMethod("subPackets");
            vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
            final Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar", true, cl);
            final Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, cl);
            final Method accessorId = accessor.getMethod("id");
            lastShared = Math.max((int) accessorId.invoke(field(avatar, "DATA_PLAYER_MODE_CUSTOMISATION").get(null)),
                    (int) accessorId.invoke(field(avatar, "DATA_PLAYER_MAIN_HAND").get(null)));
            // (last: once set, forWatcher starts)
            dataClass = dataC;
            removeClass = removeC;
            bundleClass = bundleC;
            addClass = addC;
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            addPacket = null;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't draw sumo bots as players (" + e + "); they're shown as mannequins.");
            return false;
        }
    }

    static Field field(final Class<?> type, final String name) throws NoSuchFieldException {
        final Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Object handle(final Object bukkit) throws ReflectiveOperationException {
        if (getHandle == null || !getHandle.getDeclaringClass().isInstance(bukkit))
            getHandle = bukkit.getClass().getMethod("getHandle");
        return getHandle.invoke(bukkit);
    }

    static void send(final Player viewer, final Object packet) throws ReflectiveOperationException {
        if (!viewer.isOnline())
            return;
        final Object player = handle(viewer);
        final Object connection = player.getClass().getField("connection").get(player);
        if (sendPacket == null)
            sendPacket = connection.getClass().getMethod("send", packetClass);
        sendPacket.invoke(connection, packet);
    }
}
