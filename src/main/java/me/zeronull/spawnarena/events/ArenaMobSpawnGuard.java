package me.zeronull.spawnarena.events;

import me.zeronull.spawnarena.Arena;
import me.zeronull.spawnarena.SpawnArena;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;

/**
 * No mobs spawn by themselves inside an arena, fight or not (dark dirt in a crystal pit would otherwise fill with
 * them between fights). Spawn eggs, commands and plugins still work, so staff can test things.
 */
public final class ArenaMobSpawnGuard implements Listener {
    private static final Set<SpawnReason> NATURAL = EnumSet.of(SpawnReason.NATURAL, SpawnReason.CHUNK_GEN,
            SpawnReason.PATROL, SpawnReason.REINFORCEMENTS, SpawnReason.JOCKEY, SpawnReason.MOUNT, SpawnReason.TRAP,
            SpawnReason.ENDER_PEARL, SpawnReason.SILVERFISH_BLOCK, SpawnReason.VILLAGE_DEFENSE,
            SpawnReason.VILLAGE_INVASION, SpawnReason.RAID, SpawnReason.LIGHTNING);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent e) {
        if (!NATURAL.contains(e.getSpawnReason()) || SpawnArena.arenas == null || !Arena.ArenaUtils.WORLD_EDIT_SUPPORT
                || !e.getLocation().getWorld().getName().equalsIgnoreCase(Arena.SPAWN_WORLD))
            return;
        if (SpawnArena.arenas.of(e.getLocation()) != null)
            e.setCancelled(true);
    }
}
