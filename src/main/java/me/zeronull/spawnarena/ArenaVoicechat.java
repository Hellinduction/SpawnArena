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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    /** Simple Voice Chat's server side, once it's said anything (for replays' recorded voices), or null */
    private static volatile de.maxhenkel.voicechat.api.VoicechatServerApi server;

    /** Simple Voice Chat's server side (to play replays' voices back through), or null if it isn't running. */
    static de.maxhenkel.voicechat.api.VoicechatServerApi serverApi() {
        return active ? server : null;
    }

    /** Whether voice chat is hooked in here (so recordVoice can record anything). */
    public static boolean hooked() {
        return active;
    }

    @Override
    public void initialize(final de.maxhenkel.voicechat.api.VoicechatApi api) {
        if (api instanceof de.maxhenkel.voicechat.api.VoicechatServerApi s)
            server = s;
    }

    public static void register() {
        final BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null)
            return;
        active = true;
        final ArenaVoicechat plugin = new ArenaVoicechat();
        service.registerPlugin(plugin);
        final String late = addLate(plugin);
        SpawnArena.INSTANCE.getLogger().info("Hooked into Simple Voice Chat" + (late == null ? "" : " (" + late + ")")
                + ": separate fights in the same arena can't hear each other.");
    }

    /**
     * Simple Voice Chat reads its plugins and their events once, when it starts. Loaded after that (PlugMan), this
     * plugin's events go straight into its event table, in place of those of earlier loads of SpawnArena (closed jars).
     * Null when Simple Voice Chat hasn't started yet (it then picks this plugin up by itself), else what was done.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String addLate(final ArenaVoicechat plugin) {
        try {
            final Class<?> managerClass = Class.forName("de.maxhenkel.voicechat.plugins.PluginManager", true,
                    BukkitVoicechatService.class.getClassLoader());
            final Object manager = managerClass.getMethod("instance").invoke(null);
            final java.lang.reflect.Field field = managerClass.getDeclaredField("events");
            field.setAccessible(true);
            final Map<Class<?>, List<Consumer<?>>> events = (Map<Class<?>, List<Consumer<?>>>) field.get(manager);
            if (events == null)
                return null;
            final Map<Class<?>, List<Consumer<?>>> mine = new HashMap<>();
            plugin.registerEvents(new EventRegistration() {
                @Override
                public <T extends de.maxhenkel.voicechat.api.events.Event> void registerEvent(final Class<T> type, final Consumer<T> event, final int priority) {
                    mine.computeIfAbsent(type, k -> new ArrayList<>()).add(event);
                }
            });
            int dropped = 0;
            final Map<Class<?>, List<Consumer<?>>> next = new HashMap<>();
            for (final Map.Entry<Class<?>, List<Consumer<?>>> e : events.entrySet()) {
                final List<Consumer<?>> list = new ArrayList<>();
                for (final Consumer<?> c : e.getValue())
                    if (isOldLoad(c))
                        dropped++;
                    else
                        list.add(c);
                next.put(e.getKey(), list);
            }
            for (final Map.Entry<Class<?>, List<Consumer<?>>> e : mine.entrySet())
                next.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            field.set(manager, (Map) next);
            return "added after it started" + (dropped > 0 ? ", " + dropped + " old listener(s) removed" : "");
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return "couldn't add its listeners: " + e;
        }
    }

    /** A listener of this class from an earlier load of SpawnArena (another class loader). */
    private static boolean isOldLoad(final Consumer<?> listener) {
        final Class<?> type = listener.getClass();
        return type.getName().startsWith(ArenaVoicechat.class.getName()) && type.getClassLoader() != ArenaVoicechat.class.getClassLoader();
    }

    /**
     * Unique to this load: Simple Voice Chat refuses a second plugin with an ID it already has, and it keeps the one from
     * before a reload (PlugMan) registered for good, so under the same ID the new load would never be hooked in.
     */
    private static final String ID = "spawnarena-" + Long.toString(System.currentTimeMillis(), 36);

    @Override
    public String getPluginId() {
        return ID;
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
            return; // (group talk is never passed on, nor recorded)
        final de.maxhenkel.voicechat.api.VoicechatServerApi api = event.getVoicechat();
        server = api;
        // A recorded match's fighter talking (recordVoice): kept with the recording, for its replay
        MatchRecorder.voice(sender.getPlayer().getUuid(), event.getPacket().getOpusEncodedData(), event.getPacket().isWhispering());
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
