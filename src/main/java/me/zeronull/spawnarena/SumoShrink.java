package me.zeronull.spawnarena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shrinking Platform (random sumo, SINGLE arenas only): after a while the platform's outer edge drops away, a ring at a
 * time, down to a small middle that always stays. The dropped blocks fall as falling blocks that vanish when they land
 * (nothing is placed or dropped), and the whole platform is put back at the end (and after a crash) by {@link SumoIce}.
 */
public final class SumoShrink implements Listener {
    private static final NamespacedKey KEY = new NamespacedKey(SpawnArena.INSTANCE, "sumo_shrink");
    /** Seconds before the first ring drops, then between rings. */
    private static final long START_TICKS = 15 * 20L, EVERY_TICKS = 5 * 20L;
    /** The platform never shrinks below this many blocks. */
    private static final int KEEP = 9;

    /** Each fight's dropped falling blocks, removed at the end if any are still in the air. */
    private static final Map<Fight, Set<Entity>> FALLING = new ConcurrentHashMap<>();

    /** Starts shrinking this fight's platform (already saved by SumoIce.record). Returns the task, to cancel at the end. */
    public static ScheduledTask start(final Fight fight) {
        final Arena arena = fight.getArena();
        final Location spawn = arena.spawnPoint1;
        final List<int[]> platform = SumoIce.platformOf(arena);
        if (spawn == null || spawn.getWorld() == null || platform.size() <= KEEP)
            return null;
        final World world = spawn.getWorld();
        double cx = 0, cz = 0;
        for (final int[] b : platform) {
            cx += b[0] + 0.5;
            cz += b[2] + 0.5;
        }
        final double centreX = cx / platform.size(), centreZ = cz / platform.size();
        final List<int[]> remaining = new ArrayList<>(platform);
        remaining.sort(Comparator.comparingDouble(b -> -Math.hypot(b[0] + 0.5 - centreX, b[2] + 0.5 - centreZ)));

        // A warning shortly before the first ring goes, so nobody's caught standing on the edge
        Threads.globalLater(() -> {
            if (fight.getState() == FightState.IN_FIGHT)
                for (final org.bukkit.entity.Player p : fight.getFighters())
                    Threads.entity(p, () -> {
                        p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                                "<red><bold>The edges start falling in 5 seconds!</bold></red> <gray>Move to the middle."));
                        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                    });
        }, START_TICKS - 5 * 20L);

        return Threads.locationTimer(spawn, task -> {
            if (fight.getState() != FightState.IN_FIGHT || remaining.size() <= KEEP) {
                task.cancel();
                return;
            }
            // The outermost ring: everything within a block of the furthest one left (never into the middle that stays)
            final double furthest = Math.hypot(remaining.get(0)[0] + 0.5 - centreX, remaining.get(0)[2] + 0.5 - centreZ);
            final List<int[]> ring = new ArrayList<>();
            for (final int[] b : remaining) {
                if (remaining.size() - ring.size() <= KEEP)
                    break;
                if (Math.hypot(b[0] + 0.5 - centreX, b[2] + 0.5 - centreZ) < furthest - 1)
                    break;
                ring.add(b);
            }
            remaining.removeAll(ring);
            for (final int[] b : ring)
                drop(fight, world.getBlockAt(b[0], b[1], b[2]));
        }, START_TICKS, EVERY_TICKS);
    }

    private static void drop(final Fight fight, final Block block) {
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(block.getLocation()) || block.getType().isAir())
            return;
        final BlockData data = block.getBlockData().clone();
        block.setType(Material.AIR, false);
        final FallingBlock falling = block.getWorld().spawn(block.getLocation().add(0.5, 0, 0.5), FallingBlock.class, f -> {
            f.setBlockData(data);
            f.setDropItem(false);
            f.setHurtEntities(false);
            f.setPersistent(false); // never saved: a restart can't leave it behind
            f.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
        FALLING.computeIfAbsent(fight, k -> ConcurrentHashMap.newKeySet()).add(falling);
    }

    /** When the fight ends: anything still falling goes. */
    public static void clear(final Fight fight) {
        final Set<Entity> left = FALLING.remove(fight);
        if (left != null)
            for (final Entity e : left)
                Threads.entity(e, () -> {
                    if (e.isValid())
                        e.remove();
                });
    }

    /** A dropped block landing (on the ground, or the bottom of the water) vanishes instead of being placed. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLand(final EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock f && f.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE)) {
            e.setCancelled(true);
            f.remove();
        }
    }
}
