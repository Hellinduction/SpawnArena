package me.zeronull.spawnarena;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Skins for sumo bots, from the server's own players: whoever's online, and the last couple of hundred who joined
 * since the server started. A bot only borrows the skin (under a profile of its own named "Bot"), and never its
 * opponent's own.
 */
public final class BotSkins implements Listener {
    private static final int KEEP = 200;
    /** Player -> their skin (the "textures" property), most recent last. */
    private static final Map<UUID, ProfileProperty> SEEN = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<UUID, ProfileProperty> eldest) {
            return this.size() > KEEP;
        }
    });

    @EventHandler
    public void onJoin(final PlayerJoinEvent e) {
        remember(e.getPlayer());
    }

    private static void remember(final Player player) {
        for (final ProfileProperty property : player.getPlayerProfile().getProperties())
            if (property.getName().equals("textures")) {
                SEEN.put(player.getUniqueId(), property);
                return;
            }
    }

    /** A random player's skin (not {@code except}'s), as a profile of the bot's own; null if there's none to borrow. */
    static PlayerProfile pick(final UUID except) {
        for (final Player p : Bukkit.getOnlinePlayers())
            remember(p);
        final List<ProfileProperty> skins = new ArrayList<>();
        synchronized (SEEN) {
            for (final Map.Entry<UUID, ProfileProperty> e : SEEN.entrySet())
                if (!e.getKey().equals(except))
                    skins.add(e.getValue());
        }
        if (skins.isEmpty())
            return null;
        final PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "Bot");
        profile.setProperty(skins.get(ThreadLocalRandom.current().nextInt(skins.size())));
        return profile;
    }
}
