package me.zeronull.spawnarena;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Simple Voice Chat: in MULTI arenas, fighters of different fights are hidden from each other, so
 * they shouldn't hear each other in proximity chat either. Everyone else (their own opponent and
 * team, spectators, voice chat groups) is unaffected. Only loaded when Simple Voice Chat is installed.
 */
public final class ArenaVoicechat implements VoicechatPlugin {

    /**
     * Voice chat can't unregister a plugin, so after a reload (PlugMan) it keeps calling this copy, whose jar is closed:
     * every class it hasn't loaded yet fails. Off once SpawnArena disables, or on the first such failure.
     */
    private static volatile boolean active;

    public static void register() {
        final BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null)
            return;
        active = true;
        service.registerPlugin(new ArenaVoicechat());
        SpawnArena.INSTANCE.getLogger().info("Hooked into Simple Voice Chat: separate fights in the same arena can't hear each other.");
    }

    @Override
    public String getPluginId() {
        return "spawnarena";
    }

    @Override
    public void registerEvents(final EventRegistration registration) {
        // Proximity voice (whispering and talking); group chat is static audio and stays untouched
        registration.registerEvent(EntitySoundPacketEvent.class, this::filter);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::filter);
        registration.registerEvent(de.maxhenkel.voicechat.api.events.MicrophonePacketEvent.class, this::relay);
        CrystalView.voiceOn = true;
    }

    /**
     * MULTI crystal arenas: fighters are in copies of the arena (other worlds) while their watchers are on spawn, so
     * proximity chat can't reach between them by itself. What they say is passed on, from where they are, to those of
     * the others close enough to hear (see {@link CrystalView#voiceRoute}). Not while they talk in a group.
     */
    public static void unregister() {
        active = false;
    }

    private void relay(final de.maxhenkel.voicechat.api.events.MicrophonePacketEvent event) {
        if (!active)
            return;
        try {
            relay0(event);
        } catch (final LinkageError | IllegalStateException e) {
            active = false;
        }
    }

    private void relay0(final de.maxhenkel.voicechat.api.events.MicrophonePacketEvent event) {
        final VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || sender.isInGroup())
            return;
        final de.maxhenkel.voicechat.api.VoicechatServerApi api = event.getVoicechat();
        CrystalView.voiceDistance = api.getVoiceChatDistance();
        final CrystalView.VoiceRoute route = CrystalView.voiceRoute(sender.getPlayer().getUuid());
        if (route == null)
            return;
        final boolean whisper = event.getPacket().isWhispering();
        final double distance = whisper ? api.getVoiceChatDistance() / 2 : api.getVoiceChatDistance();
        final de.maxhenkel.voicechat.api.packets.LocationalSoundPacket packet = event.getPacket().locationalSoundPacketBuilder()
                .position(api.createPosition(route.x(), route.y(), route.z()))
                .distance((float) distance)
                .build();
        for (final CrystalView.VoiceRoute.Ear ear : route.ears()) {
            if (ear.distance() > distance)
                continue;
            final VoicechatConnection to = api.getConnectionOf(ear.player());
            if (to != null && to.isConnected() && !to.isDisabled())
                api.sendLocationalSoundPacketTo(to, packet);
        }
    }

    private void filter(final SoundPacketEvent<?> event) {
        if (!active)
            return;
        try {
            filter0(event);
        } catch (final LinkageError | IllegalStateException e) {
            active = false;
        }
    }

    private void filter0(final SoundPacketEvent<?> event) {
        final Player sender = bukkitPlayer(event.getSenderConnection());
        final Player receiver = bukkitPlayer(event.getReceiverConnection());
        if (sender != null && receiver != null && hiddenFromEachOther(sender, receiver))
            event.cancel();
    }

    private static Player bukkitPlayer(final VoicechatConnection connection) {
        if (connection == null)
            return null;
        return connection.getPlayer().getPlayer() instanceof Player p ? p : null;
    }

    /** The same rule as Fight's hiding: both fighting, in the same MULTI arena, in different fights that are under way. */
    static boolean hiddenFromEachOther(final Player a, final Player b) {
        if (SpawnArena.arenas == null)
            return false;
        final Arena arena = SpawnArena.arenas.of(a);
        if (arena == null || arena.getArenaMode() != ArenaMode.MULTI || !arena.equals(SpawnArena.arenas.of(b)))
            return false;
        final Fight fa = arena.getFight(a), fb = arena.getFight(b);
        return fa != null && fb != null && !fa.equals(fb) && underWay(fa) && underWay(fb);
    }

    private static boolean underWay(final Fight fight) {
        return fight.getState() == FightState.PRE_FIGHT_START || fight.getState() == FightState.IN_FIGHT;
    }
}
