package me.zeronull.spawnarena;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outlines everyone in a live fight in that fight's own colour (vanilla glowing), so spectators can tell
 * matches apart at a glance. Each running fight gets a colour no other running fight has. The outline goes as
 * soon as the fight is over for that player.
 *
 * <p>A glow takes the colour of the team the viewer's client has that player on. The server's own scoreboard
 * is never touched, as that would also colour their name in chat and join/quit messages. Instead:
 * <ul>
 *   <li>with TAB (which runs everyone's teams itself and blocks other plugins' team changes), the fight colour
 *   goes on the end of their TAB nametag prefix for the fight, and TAB colours the team after it;</li>
 *   <li>without it, clients are sent a team just for the fight, as packets.</li>
 * </ul>
 * Their nametag above their head takes the colour too: the game colours the name and the glow from the same
 * team. If neither way works (e.g. a server update renamed what the packets use), they glow plain white.
 *
 * <p>A player's glowing flag is saved with their player data, so it's marked, and a crash's leftovers are
 * undone when they next join.
 */
public final class FightGlow implements Listener {
    private static final String TEAM_PREFIX = "sa_glow_";
    /** Bright, easy-to-tell-apart colours first */
    private static final NamedTextColor[] COLOURS = {
            NamedTextColor.RED, NamedTextColor.AQUA, NamedTextColor.YELLOW, NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.GREEN, NamedTextColor.GOLD, NamedTextColor.BLUE, NamedTextColor.WHITE,
            NamedTextColor.DARK_AQUA, NamedTextColor.DARK_PURPLE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_RED,
            NamedTextColor.GRAY, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GRAY, NamedTextColor.BLACK};

    /** Running fight -> index into COLOURS */
    private static final Map<Fight, Integer> COLOUR_OF = new ConcurrentHashMap<>();
    /** Fighter -> the colour they're outlined in right now */
    private static final Map<UUID, Integer> GLOWING = new ConcurrentHashMap<>();

    private static NamespacedKey markKey() {
        return new NamespacedKey(SpawnArena.INSTANCE, "fight_glow");
    }

    private static boolean enabled() {
        return SpawnArena.settings == null || SpawnArena.settings.fightGlow;
    }

    /** When a fight goes live: picks it a free colour and outlines its fighters. */
    public static void start(final Fight fight) {
        if (!enabled())
            return;
        final int colour;
        synchronized (COLOUR_OF) {
            int free = -1;
            for (int i = 0; i < COLOURS.length && free < 0; i++)
                if (!COLOUR_OF.containsValue(i))
                    free = i;
            colour = free >= 0 ? free : COLOUR_OF.size() % COLOURS.length; // more fights than colours: share
            COLOUR_OF.put(fight, colour);
        }

        for (final Player fighter : fight.getFighters()) {
            // Checked again on their thread: they may already be out by the time it runs
            Threads.entity(fighter, () -> {
                if (!fight.isFighter(fighter) || !COLOUR_OF.containsKey(fight) || !fighter.isOnline())
                    return;
                fighter.setGlowing(true);
                fighter.getPersistentDataContainer().set(markKey(), PersistentDataType.BYTE, (byte) 1);
                GLOWING.put(fighter.getUniqueId(), colour);
                colour(fighter, colour);
            });
        }
    }

    /** When the fight is over: frees its colour. Each member's outline is taken off by {@link #remove}. */
    public static void end(final Fight fight) {
        COLOUR_OF.remove(fight);
    }

    /** Takes this player's outline off. Safe to call more than once. */
    public static void remove(final Player player) {
        final Integer colour = GLOWING.remove(player.getUniqueId());
        if (colour != null)
            uncolour(player, colour);
        Threads.entity(player, () -> {
            if (player.getPersistentDataContainer().has(markKey(), PersistentDataType.BYTE)) {
                player.setGlowing(false);
                player.getPersistentDataContainer().remove(markKey());
            }
        });
    }

    /** On startup: teams an older version made on the server scoreboard go (and their colour with them). */
    public static void cleanUpOnEnable() {
        Threads.global(() -> {
            try {
                final org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager == null)
                    return;
                for (final Team team : manager.getMainScoreboard().getTeams())
                    if (team.getName().startsWith(TEAM_PREFIX))
                        team.unregister();
            } catch (final RuntimeException ignored) {
                // e.g. Folia refusing the scoreboard: there's nothing of ours on it then either
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        final Player joined = e.getPlayer();
        // An outline a crash left behind (nobody who just joined is in a fight)
        Threads.entity(joined, () -> {
            if (!joined.getPersistentDataContainer().has(markKey(), PersistentDataType.BYTE))
                return;
            joined.setGlowing(false);
            joined.getPersistentDataContainer().remove(markKey());
        });
        // Fights already running: their colours, for this client
        if (!GLOWING.isEmpty() && !TabColours.usable())
            PacketTeams.showRunningTo(joined);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent e) {
        PacketTeams.forget(e.getPlayer());
    }

    // ── colouring ───────────────────────────────────────────────────────────

    private static void colour(final Player fighter, final int colour) {
        if (TabColours.usable()) {
            TabColours.colour(fighter, COLOURS[colour]);
            return;
        }
        PacketTeams.add(fighter, colour);
    }

    private static void uncolour(final Player fighter, final int colour) {
        if (TabColours.usable()) {
            TabColours.uncolour(fighter);
            return;
        }
        PacketTeams.remove(fighter, colour);
    }

    private static ChatColor legacy(final NamedTextColor colour) {
        return ChatColor.valueOf(colour.toString().toUpperCase(java.util.Locale.ROOT));
    }

    /** Through TAB's API, looked up at runtime so TAB stays optional. */
    private static final class TabColours {
        private static volatile boolean checked, usable;
        private static Object api;
        private static Method getPlayer, getNameTagManager, setPrefix, getOriginalRawPrefix;

        static boolean usable() {
            if (!checked)
                init();
            return usable;
        }

        private static synchronized void init() {
            if (checked)
                return;
            checked = true;
            final Plugin tab = Bukkit.getPluginManager().getPlugin("TAB");
            if (tab == null || !tab.isEnabled())
                return;
            try {
                final ClassLoader loader = tab.getClass().getClassLoader();
                final Class<?> apiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, loader);
                final Class<?> tabPlayer = Class.forName("me.neznamy.tab.api.TabPlayer", true, loader);
                final Class<?> nameTags = Class.forName("me.neznamy.tab.api.nametag.NameTagManager", true, loader);
                api = apiClass.getMethod("getInstance").invoke(null);
                getPlayer = apiClass.getMethod("getPlayer", UUID.class);
                getNameTagManager = apiClass.getMethod("getNameTagManager");
                setPrefix = nameTags.getMethod("setPrefix", tabPlayer, String.class);
                getOriginalRawPrefix = nameTags.getMethod("getOriginalRawPrefix", tabPlayer);
                // Null when TAB's team handling is turned off; then it won't get in the way of our own teams
                usable = getNameTagManager.invoke(api) != null;
                SpawnArena.INSTANCE.getLogger().info(usable
                        ? "TAB found: fight outlines are coloured through its nametag prefixes."
                        : "TAB found, but its team handling is off: fight outlines use their own teams.");
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't hook into TAB's API (" + e
                        + "); fight outlines will be white while TAB runs the teams.");
            }
        }

        static void colour(final Player fighter, final NamedTextColor colour) {
            try {
                final Object player = getPlayer.invoke(api, fighter.getUniqueId());
                if (player == null)
                    return;
                final Object nameTags = getNameTagManager.invoke(api);
                final Object prefix = getOriginalRawPrefix.invoke(nameTags, player);
                // TAB colours the team (so the glow) after the prefix's last colour
                setPrefix.invoke(nameTags, player, (prefix == null ? "" : prefix) + "&" + legacy(colour).getChar());
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't colour " + fighter.getName() + "'s outline through TAB: " + e);
            }
        }

        static void uncolour(final Player fighter) {
            try {
                final Object player = getPlayer.invoke(api, fighter.getUniqueId());
                if (player != null)
                    setPrefix.invoke(getNameTagManager.invoke(api), player, null);   // back to their normal prefix
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't reset " + fighter.getName() + "'s TAB prefix: " + e);
            }
        }
    }

    /**
     * Teams sent straight to clients, one per colour, on a scoreboard of our own that the server never uses.
     * Server internals, looked up by name at runtime (Paper and Folia use the same names).
     */
    private static final class PacketTeams {
        private static boolean broken;
        private static Object[] teams;
        private static Method getPlayers, addOrModify, playerPacket, send;
        private static Object add, remove;
        private static Class<?> packetClass;
        /** Viewer -> which of our teams their client has */
        private static final Map<UUID, BitSet> CREATED = new ConcurrentHashMap<>();

        private static boolean ready() {
            if (teams != null)
                return true;
            if (broken)
                return false;
            try {
                final ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
                final Class<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard", true, cl);
                final Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam", true, cl);
                final Class<?> formatting = Class.forName("net.minecraft.ChatFormatting", true, cl);
                final Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket", true, cl);
                final Class<?> action = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket$Action", true, cl);
                packetClass = Class.forName("net.minecraft.network.protocol.Packet", true, cl);

                final Object board = scoreboard.getConstructor().newInstance();
                final Constructor<?> newTeam = playerTeam.getConstructor(scoreboard, String.class);
                final Method setColor = playerTeam.getMethod("setColor", formatting);
                final Method setSeeFriendlyInvisibles = playerTeam.getMethod("setSeeFriendlyInvisibles", boolean.class);
                getPlayers = playerTeam.getMethod("getPlayers");
                addOrModify = packet.getMethod("createAddOrModifyPacket", playerTeam, boolean.class);
                playerPacket = packet.getMethod("createPlayerPacket", playerTeam, String.class, action);
                add = action.getField("ADD").get(null);
                remove = action.getField("REMOVE").get(null);

                final Object[] made = new Object[COLOURS.length];
                for (int i = 0; i < COLOURS.length; i++) {
                    made[i] = newTeam.newInstance(board, TEAM_PREFIX + i);
                    setColor.invoke(made[i], formatting.getField(COLOURS[i].toString().toUpperCase(java.util.Locale.ROOT)).get(null));
                    // Opponents share the team: an invisibility potion must still hide you from them
                    setSeeFriendlyInvisibles.invoke(made[i], false);
                }
                teams = made;
                return true;
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                broken = true;
                SpawnArena.INSTANCE.getLogger().warning("Couldn't make the teams that colour fight outlines (" + e
                        + "); fighters will glow white instead.");
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        private static Collection<String> members(final int colour) throws ReflectiveOperationException {
            return (Collection<String>) getPlayers.invoke(teams[colour]);
        }

        static synchronized void add(final Player fighter, final int colour) {
            if (!ready())
                return;
            try {
                members(colour).add(fighter.getName());
                for (final Player viewer : Bukkit.getOnlinePlayers()) {
                    final BitSet has = CREATED.computeIfAbsent(viewer.getUniqueId(), k -> new BitSet());
                    if (has.get(colour)) {
                        send(viewer, playerPacket.invoke(null, teams[colour], fighter.getName(), add));
                    } else {
                        send(viewer, addOrModify.invoke(null, teams[colour], true));
                        has.set(colour);
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't colour " + fighter.getName() + "'s outline: " + e);
            }
        }

        static synchronized void remove(final Player fighter, final int colour) {
            if (!ready())
                return;
            try {
                members(colour).remove(fighter.getName());
                for (final Player viewer : Bukkit.getOnlinePlayers()) {
                    final BitSet has = CREATED.get(viewer.getUniqueId());
                    if (has != null && has.get(colour))
                        send(viewer, playerPacket.invoke(null, teams[colour], fighter.getName(), remove));
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't take " + fighter.getName() + "'s outline colour off: " + e);
            }
            // Leaving our team leaves them on none, client-side: if they're really on a team (another plugin's,
            // on the server scoreboard), have it sent out again
            final String name = fighter.getName();
            Threads.global(() -> {
                try {
                    final Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                    final Team real = board.getEntryTeam(name);
                    if (real != null) {
                        real.removeEntry(name);
                        real.addEntry(name);
                    }
                } catch (final RuntimeException ignored) {
                    // no scoreboard (e.g. Folia): nothing to put back
                }
            });
        }

        /** A player who just joined: the colours of fights already running. */
        static synchronized void showRunningTo(final Player viewer) {
            if (!ready())
                return;
            try {
                final BitSet has = CREATED.computeIfAbsent(viewer.getUniqueId(), k -> new BitSet());
                for (int colour = 0; colour < teams.length; colour++) {
                    if (members(colour).isEmpty() || has.get(colour))
                        continue;
                    send(viewer, addOrModify.invoke(null, teams[colour], true));
                    has.set(colour);
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't show fight outline colours to " + viewer.getName() + ": " + e);
            }
        }

        static void forget(final Player viewer) {
            CREATED.remove(viewer.getUniqueId());
        }

        private static void send(final Player viewer, final Object packet) throws ReflectiveOperationException {
            final Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
            final Object connection = handle.getClass().getField("connection").get(handle);
            if (send == null)
                send = connection.getClass().getMethod("send", packetClass);
            send.invoke(connection, packet);
        }
    }
}
