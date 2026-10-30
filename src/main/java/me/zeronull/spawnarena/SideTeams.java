package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * For {@link FightGlow}, with fightGlowSides on: each fighter is put on a team of their own, sent as packets, whose
 * colour depends on who's looking. The others in their match see their own side green and the other side red; everyone
 * else sees the fight's colour. The team keeps their name tag's prefix and suffix (their rank, from TAB), and TAB's own
 * team for them is paused for the match (it would fight over who they're shown with) and resumed after.
 */
final class SideTeams {
    private static final NamedTextColor OWN_SIDE = NamedTextColor.GREEN;
    private static final NamedTextColor OTHER_SIDE = NamedTextColor.RED;

    private static boolean broken;
    private static Constructor<?> newScoreboard, newTeam;
    private static Method setColor, setPrefix, setSuffix, getPlayers, addOrModify, removePacket, asVanilla, sendPacket;
    private static Class<?> formatting, packetClass;

    /** A fighter shown on their team: which fight, its colour for everyone else, and their name tag's ends. */
    private record Shown(Fight fight, NamedTextColor colour, String team, Component prefix, Component suffix) {}

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private SideTeams() {}

    static synchronized boolean ready() {
        if (setColor != null)
            return true;
        if (broken)
            return false;
        try {
            final ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            final Class<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard", true, cl);
            final Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam", true, cl);
            final Class<?> component = Class.forName("net.minecraft.network.chat.Component", true, cl);
            final Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket", true, cl);
            formatting = Class.forName("net.minecraft.ChatFormatting", true, cl);
            packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);
            newScoreboard = scoreboard.getConstructor();
            newTeam = playerTeam.getConstructor(scoreboard, String.class);
            setPrefix = playerTeam.getMethod("setPlayerPrefix", component);
            setSuffix = playerTeam.getMethod("setPlayerSuffix", component);
            getPlayers = playerTeam.getMethod("getPlayers");
            addOrModify = packet.getMethod("createAddOrModifyPacket", playerTeam, boolean.class);
            removePacket = packet.getMethod("createRemovePacket", playerTeam);
            asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure", true, cl)
                    .getMethod("asVanilla", net.kyori.adventure.text.Component.class);
            setColor = playerTeam.getMethod("setColor", formatting);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            setColor = null;
            SpawnArena.INSTANCE.getLogger().warning("Couldn't make the green/red side teams (" + e + "); fighters see fight colours instead.");
            return false;
        }
    }

    private static String teamName(final UUID fighter) {
        return "sa_side_" + fighter.toString().substring(0, 8);
    }

    /** What this viewer sees them as: green for their own side, red for the other, the fight's colour for anyone else. */
    private static NamedTextColor colourFor(final Shown shown, final UUID fighter, final Player viewer) {
        final Fight fight = shown.fight();
        Player fighterPlayer = null;
        boolean viewerIn = false;
        for (final Player member : fight.getMembers()) {
            if (member.getUniqueId().equals(viewer.getUniqueId()))
                viewerIn = true;
            if (member.getUniqueId().equals(fighter))
                fighterPlayer = member;
        }
        if (!viewerIn || fighterPlayer == null)
            return shown.colour();
        return fight.getTeamOf(fighterPlayer).contains(viewer) ? OWN_SIDE : OTHER_SIDE;
    }

    private static Object packetFor(final Shown shown, final String entry, final NamedTextColor colour) throws ReflectiveOperationException {
        final Object team = newTeam.newInstance(newScoreboard.newInstance(), shown.team());
        setColor.invoke(team, formatting.getField(colour.toString().toUpperCase(java.util.Locale.ROOT)).get(null));
        if (shown.prefix() != null)
            setPrefix.invoke(team, asVanilla.invoke(null, shown.prefix()));
        if (shown.suffix() != null)
            setSuffix.invoke(team, asVanilla.invoke(null, shown.suffix()));
        @SuppressWarnings("unchecked") final Collection<String> players = (Collection<String>) getPlayers.invoke(team);
        players.add(entry);
        return addOrModify.invoke(null, team, true);
    }

    /** Puts this fighter on their side team, for every player online. */
    static void show(final Player fighter, final Fight fight, final NamedTextColor colour) {
        if (!ready())
            return;
        final Component[] ends = Tab.pause(fighter);
        final Shown shown = new Shown(fight, colour, teamName(fighter.getUniqueId()), ends[0], ends[1]);
        SHOWN.put(fighter.getUniqueId(), shown);
        final UUID id = fighter.getUniqueId();
        final String name = fighter.getName();
        final Runnable sendAll = () -> {
            if (SHOWN.get(id) != shown)
                return; // out of the match meanwhile
            for (final Player viewer : Bukkit.getOnlinePlayers())
                sendTo(viewer, id, name, shown);
        };
        sendAll.run();
        // TAB pauses its team in the background, and until then strips other plugins' team packets for them: again once it has
        Threads.globalLater(sendAll, 10L);
        Threads.globalLater(sendAll, 40L);
    }

    private static void sendTo(final Player viewer, final UUID fighter, final String name, final Shown shown) {
        try {
            send(viewer, packetFor(shown, name, colourFor(shown, fighter, viewer)));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't show " + name + "'s side colour to " + viewer.getName() + ": " + e);
        }
    }

    /** Off their side team, back to their usual one (TAB's). Safe to call more than once. */
    static void hide(final Player fighter) {
        final Shown shown = SHOWN.remove(fighter.getUniqueId());
        if (shown == null || !ready())
            return;
        try {
            final Object team = newTeam.newInstance(newScoreboard.newInstance(), shown.team());
            final Object packet = removePacket.invoke(null, team);
            for (final Player viewer : Bukkit.getOnlinePlayers())
                send(viewer, packet);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            SpawnArena.INSTANCE.getLogger().warning("Couldn't take " + fighter.getName() + "'s side colour off: " + e);
        }
        Tab.resume(fighter);
    }

    /** Someone who just joined: everyone's side teams, as they see them. */
    static void showRunningTo(final Player viewer) {
        for (final Map.Entry<UUID, Shown> e : SHOWN.entrySet()) {
            final Player fighter = Bukkit.getPlayer(e.getKey());
            if (fighter != null)
                sendTo(viewer, e.getKey(), fighter.getName(), e.getValue());
        }
    }

    static boolean isShown(final UUID fighter) {
        return SHOWN.containsKey(fighter);
    }

    private static void send(final Player viewer, final Object packet) throws ReflectiveOperationException {
        final Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        final Object connection = handle.getClass().getField("connection").get(handle);
        if (sendPacket == null)
            sendPacket = connection.getClass().getMethod("send", packetClass);
        sendPacket.invoke(connection, packet);
    }

    /** TAB, if it's there: its team for a fighter paused for the match, and their name tag's prefix and suffix. */
    private static final class Tab {
        private static boolean checked;
        private static Object api;
        private static Method getPlayer, getNameTagManager, pause, resume, prefix, suffix;

        private static synchronized boolean ready() {
            if (checked)
                return api != null;
            checked = true;
            final Plugin tab = Bukkit.getPluginManager().getPlugin("TAB");
            if (tab == null || !tab.isEnabled())
                return false;
            try {
                final ClassLoader loader = tab.getClass().getClassLoader();
                final Class<?> apiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, loader);
                final Class<?> tabPlayer = Class.forName("me.neznamy.tab.api.TabPlayer", true, loader);
                final Class<?> nameTags = Class.forName("me.neznamy.tab.api.nametag.NameTagManager", true, loader);
                getPlayer = apiClass.getMethod("getPlayer", UUID.class);
                getNameTagManager = apiClass.getMethod("getNameTagManager");
                pause = nameTags.getMethod("pauseTeamHandling", tabPlayer);
                resume = nameTags.getMethod("resumeTeamHandling", tabPlayer);
                prefix = nameTags.getMethod("getOriginalReplacedPrefix", tabPlayer);
                suffix = nameTags.getMethod("getOriginalReplacedSuffix", tabPlayer);
                final Object found = apiClass.getMethod("getInstance").invoke(null);
                if (getNameTagManager.invoke(found) != null)
                    api = found; // null when TAB's team handling is off: nothing to pause then
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't use TAB's API for the side teams (" + e + ").");
            }
            return api != null;
        }

        /** Pauses TAB's team for them; returns their name tag's {prefix, suffix} (nulls without TAB). */
        static Component[] pause(final Player fighter) {
            if (!ready())
                return new Component[2];
            try {
                final Object player = getPlayer.invoke(api, fighter.getUniqueId());
                if (player == null)
                    return new Component[2];
                final Object nameTags = getNameTagManager.invoke(api);
                final Component[] ends = {legacy((String) prefix.invoke(nameTags, player)), legacy((String) suffix.invoke(nameTags, player))};
                pause.invoke(nameTags, player);
                return ends;
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't pause TAB's team for " + fighter.getName() + ": " + e);
                return new Component[2];
            }
        }

        static void resume(final Player fighter) {
            if (!ready())
                return;
            try {
                final Object player = getPlayer.invoke(api, fighter.getUniqueId());
                if (player != null)
                    resume.invoke(getNameTagManager.invoke(api), player);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't resume TAB's team for " + fighter.getName() + ": " + e);
            }
        }

        private static Component legacy(final String text) {
            if (text == null || text.isEmpty())
                return null;
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                    .character('&').hexColors().build().deserialize(text.replace('§', '&'));
        }
    }
}
