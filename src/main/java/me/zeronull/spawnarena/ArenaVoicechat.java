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

    public static void register() {
        final BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null)
            return;
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
    }

    private void filter(final SoundPacketEvent<?> event) {
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
