package me.zeronull.spawnarena.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network-wide arena win announcements for SpawnArena. The arena server sends each announcement here
 * and this shows it to every player on every server (except the fighters, who already saw it, and
 * anyone who turned announcements off with /arenawins).
 *
 * <p>Only messages from backend servers are accepted: anything a player's client sends on this
 * channel is dropped, so nobody can fake an announcement.
 */
@Plugin(id = "spawnarenabridge", name = "SpawnArenaBridge", version = "1.0.0",
        description = "Network-wide SpawnArena win announcements", authors = {"Hellinduction"})
public final class SpawnArenaBridge {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("spawnarena:main");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path hiddenFile;
    /** Players who turned announcements off (/arenawins), saved so it survives proxy restarts. */
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    @Inject
    public SpawnArenaBridge(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.hiddenFile = dataDirectory.resolve("hidden-announcements.txt");
    }

    @Subscribe
    public void onInit(final ProxyInitializeEvent event) {
        // Registering tells backend servers this channel is handled here (that's how they detect the bridge)
        this.proxy.getChannelRegistrar().register(CHANNEL);
        this.loadHidden();
    }

    @Subscribe
    public void onMessage(final PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier()))
            return;
        // Never pass these on to anyone, whoever sent them
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // From a backend server only; a player's client can't use this channel
        if (!(event.getSource() instanceof ServerConnection server))
            return;

        try {
            final ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            switch (in.readUTF()) {
                case "hello" -> {
                    final String code = in.readUTF();
                    this.setHidden(UUID.fromString(in.readUTF()), in.readBoolean());
                    final ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("ack");
                    out.writeUTF(code);
                    server.sendPluginMessage(CHANNEL, out.toByteArray());
                }
                case "pref" -> this.setHidden(UUID.fromString(in.readUTF()), in.readBoolean());
                case "broadcast" -> {
                    final Component message = GsonComponentSerializer.gson().deserialize(in.readUTF());
                    final int count = in.readInt();
                    final Set<UUID> alreadyTold = new HashSet<>();
                    for (int i = 0; i < count; i++)
                        alreadyTold.add(UUID.fromString(in.readUTF()));

                    for (final Player player : this.proxy.getAllPlayers())
                        if (!alreadyTold.contains(player.getUniqueId()) && !this.hidden.contains(player.getUniqueId()))
                            player.sendMessage(message);
                }
                default -> { }
            }
        } catch (final Exception e) {
            this.logger.warn("Ignored a malformed SpawnArena message from {}: {}", server.getServerInfo().getName(), e.toString());
        }
    }

    private void setHidden(final UUID player, final boolean hide) {
        final boolean changed = hide ? this.hidden.add(player) : this.hidden.remove(player);
        if (changed)
            this.saveHidden();
    }

    private void loadHidden() {
        try {
            if (!Files.exists(this.hiddenFile))
                return;
            for (final String line : Files.readAllLines(this.hiddenFile, StandardCharsets.UTF_8)) {
                try {
                    if (!line.isBlank())
                        this.hidden.add(UUID.fromString(line.trim()));
                } catch (final IllegalArgumentException ignored) {
                }
            }
        } catch (final IOException e) {
            this.logger.warn("Couldn't read {}: {}", this.hiddenFile, e.toString());
        }
    }

    private synchronized void saveHidden() {
        try {
            Files.createDirectories(this.hiddenFile.getParent());
            Files.write(this.hiddenFile, List.copyOf(this.hidden).stream().map(UUID::toString).toList(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            this.logger.warn("Couldn't save {}: {}", this.hiddenFile, e.toString());
        }
    }
}
