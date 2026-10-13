package me.zeronull.spawnarena;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;

/**
 * Talks to the SpawnArena bridge on a Velocity proxy, so win announcements reach the whole network.
 *
 * <p>A backend can't see the proxy's plugins, so the bridge has to prove it's there: when a player
 * joins, this sends a "hello" with a random code through their connection, and only the bridge answers
 * with that code. Until then (or with no bridge at all), announcements are made in this server's chat
 * as usual. The bridge only accepts messages coming from backend servers, never from players' clients.
 */
public final class ProxyBridge implements PluginMessageListener, Listener {
    public static final String CHANNEL = "spawnarena:main";

    private static final String CODE = UUID.randomUUID().toString();
    private static volatile boolean confirmed;

    private ProxyBridge() {}

    public static void enable() {
        final ProxyBridge bridge = new ProxyBridge();
        Bukkit.getMessenger().registerOutgoingPluginChannel(SpawnArena.INSTANCE, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(SpawnArena.INSTANCE, CHANNEL, bridge);
        Bukkit.getPluginManager().registerEvents(bridge, SpawnArena.INSTANCE);
        // Players already online (e.g. after a reload)
        for (final Player p : Bukkit.getOnlinePlayers())
            sayHello(p);
    }

    /** Whether a bridge has answered and there's someone online to carry a message to it. */
    public static boolean isAvailable() {
        return confirmed && SpawnArena.settings != null && SpawnArena.settings.proxyBroadcast && carrier(Bukkit.getOnlinePlayers()) != null;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent e) {
        final Player player = e.getPlayer();
        // Give the proxy a moment to tell the server which channels it handles
        Threads.entityLater(player, () -> sayHello(player), 20L);
    }

    private static void sayHello(final Player player) {
        if (!player.isOnline() || SpawnArena.settings == null || !SpawnArena.settings.proxyBroadcast)
            return;
        // The proxy announces the channels its plugins handle; no bridge means nobody's listening
        if (!player.getListeningPluginChannels().contains(CHANNEL))
            return;
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("hello");
        out.writeUTF(CODE);
        // Their /arenawins choice, so the bridge respects it on every server
        out.writeUTF(player.getUniqueId().toString());
        out.writeBoolean(!WinMessagePrefs.wantsWinMessages(player));
        player.sendPluginMessage(SpawnArena.INSTANCE, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(@NotNull final String channel, @NotNull final Player player, final byte @NotNull [] message) {
        if (!CHANNEL.equals(channel))
            return;
        try {
            final ByteArrayDataInput in = ByteStreams.newDataInput(message);
            if (!"ack".equals(in.readUTF()) || !CODE.equals(in.readUTF()))
                return;
            if (!confirmed)
                SpawnArena.INSTANCE.getLogger().info("Velocity bridge found: arena win announcements will go to the whole network.");
            confirmed = true;
        } catch (final Exception ignored) {
            // not ours / malformed
        }
    }

    /** Tells the bridge a player changed their /arenawins setting. */
    public static void sendPreference(final Player player) {
        if (!confirmed || !player.isOnline())
            return;
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("pref");
        out.writeUTF(player.getUniqueId().toString());
        out.writeBoolean(!WinMessagePrefs.wantsWinMessages(player));
        player.sendPluginMessage(SpawnArena.INSTANCE, CHANNEL, out.toByteArray());
    }

    /**
     * Asks the bridge to show this message to everyone on the network except {@code alreadyTold} (and
     * anyone who turned announcements off). Returns false if it couldn't be sent, so the caller can
     * announce locally instead.
     */
    public static boolean broadcast(final Component message, final Collection<Player> preferredCarriers,
                                    final Collection<Player> alreadyTold) {
        if (!isAvailable())
            return false;
        Player carrier = carrier(preferredCarriers);
        if (carrier == null)
            carrier = carrier(Bukkit.getOnlinePlayers());
        if (carrier == null)
            return false;

        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("broadcast");
        out.writeUTF(GsonComponentSerializer.gson().serialize(message));
        out.writeInt(alreadyTold.size());
        for (final Player p : alreadyTold)
            out.writeUTF(p.getUniqueId().toString());
        carrier.sendPluginMessage(SpawnArena.INSTANCE, CHANNEL, out.toByteArray());
        return true;
    }

    /** An online player whose connection the proxy is listening to on our channel. */
    private static Player carrier(final Collection<? extends Player> players) {
        for (final Player p : players)
            if (p.isOnline() && p.getListeningPluginChannels().contains(CHANNEL))
                return p;
        return null;
    }
}
