package me.zeronull.spawnarena;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A replay's recorded voice chat (recordVoice), played back through Simple Voice Chat: each fighter's voice from where
 * they're drawn, to the replay's viewer alone. Only loaded when Simple Voice Chat is there (see {@link ArenaVoicechat}).
 */
final class ReplayVoice {
    private final VoicechatServerApi api;
    private final UUID viewer;
    private final World world;
    /** Each fighter's voice (their place in the recording) */
    private final Map<Integer, LocationalAudioChannel> channels = new HashMap<>();

    private ReplayVoice(final VoicechatServerApi api, final Player viewer, final World world) {
        this.api = api;
        this.viewer = viewer.getUniqueId();
        this.world = world;
    }

    /** For this viewer, or null if Simple Voice Chat isn't running. */
    static ReplayVoice of(final Player viewer, final World world) {
        final VoicechatServerApi api = ArenaVoicechat.serverApi();
        return api == null ? null : new ReplayVoice(api, viewer, world);
    }

    /** Plays one recorded voice packet of this fighter's, from where they are, to the viewer only. */
    void play(final int fighter, final double x, final double y, final double z, final byte[] opus, final boolean whisper) {
        try {
            LocationalAudioChannel channel = this.channels.get(fighter);
            if (channel == null || channel.isClosed()) {
                channel = this.api.createLocationalAudioChannel(UUID.randomUUID(), this.api.fromServerLevel(this.world), this.api.createPosition(x, y, z));
                if (channel == null)
                    return;
                final UUID only = this.viewer;
                channel.setFilter(p -> p.getUuid().equals(only));
                this.channels.put(fighter, channel);
            }
            channel.setDistance((float) (whisper ? this.api.getVoiceChatDistance() / 2 : this.api.getVoiceChatDistance()));
            channel.updateLocation(this.api.createPosition(x, y, z));
            channel.send(opus);
        } catch (final RuntimeException | LinkageError ignored) {
            // voice chat went away: just quiet
        }
    }

    /** The replay's paused, skipped or stopped: what's buffered is let go. */
    void flush() {
        for (final LocationalAudioChannel channel : this.channels.values())
            try {
                channel.flush();
            } catch (final RuntimeException | LinkageError ignored) {
                // gone
            }
    }
}
