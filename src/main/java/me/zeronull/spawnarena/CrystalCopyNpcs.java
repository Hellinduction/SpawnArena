package me.zeronull.spawnarena;

import com.sk89q.worldedit.math.BlockVector3;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.actions.NpcAction;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FancyNpcs NPCs standing in a MULTI crystal arena's copied area (a menu NPC on the deck, say), copied into each copy at
 * the same spot with everything they do on click. The copies are never saved, and go when the copies are retired or
 * the server stops. Only touch this class after checking FancyNpcs is enabled: it links against its API.
 */
final class CrystalCopyNpcs {
    /** Copy world -> the NPCs made in it */
    private static final Map<UUID, List<Npc>> MADE = new ConcurrentHashMap<>();

    private CrystalCopyNpcs() {}

    /** Copies every NPC in the box (in {@code from}) into {@code to}, replacing ones copied there before. */
    static void copy(final World from, final World to, final BlockVector3 min, final BlockVector3 max) {
        removeFrom(to);
        final List<Npc> made = new ArrayList<>();
        for (final Npc npc : new ArrayList<>(FancyNpcsPlugin.get().getNpcManager().getAllNpcs())) {
            final NpcData d = npc.getData();
            final Location at = d.getLocation();
            if (at == null || at.getWorld() == null || !at.getWorld().equals(from) || !inside(at, min, max))
                continue;
            final Location there = at.clone();
            there.setWorld(to);
            final Map<ActionTrigger, List<NpcAction.NpcActionData>> actions = new HashMap<>();
            d.getActions().forEach((trigger, list) -> actions.put(trigger, new ArrayList<>(list)));
            final NpcData copy = new NpcData(UUID.randomUUID().toString(), d.getName() + "_" + to.getName(), d.getCreator(),
                    d.getDisplayName(), d.getSkinData(), there, d.isShowInTab(), d.isSpawnEntity(), d.isCollidable(),
                    d.isGlowing(), d.getGlowingColor(), d.getType(), new HashMap<>(d.getEquipment()), d.isTurnToPlayer(),
                    d.getTurnToPlayerDistance(), d.getOnClick(), actions, d.getInteractionCooldown(), d.getScale(),
                    d.getVisibilityDistance(), new HashMap<>(d.getAttributes()), d.isMirrorSkin());
            try {
                final Npc made1 = FancyNpcsPlugin.get().getNpcAdapter().apply(copy);
                made1.setSaveToFile(false);
                FancyNpcsPlugin.get().getNpcManager().registerNpc(made1);
                made1.create();
                made1.spawnForAll();
                made.add(made1);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't copy NPC " + d.getName() + " into " + to.getName() + ": " + e);
            }
        }
        if (!made.isEmpty()) {
            MADE.put(to.getUID(), made);
            SpawnArena.INSTANCE.getLogger().info("Copied " + made.size() + " NPC(s) into " + to.getName());
        }
    }

    private static boolean inside(final Location at, final BlockVector3 min, final BlockVector3 max) {
        return at.getBlockX() >= min.x() && at.getBlockX() <= max.x() && at.getBlockY() >= min.y() && at.getBlockY() <= max.y()
                && at.getBlockZ() >= min.z() && at.getBlockZ() <= max.z();
    }

    /** The NPCs copied into this world go. */
    static void removeFrom(final World world) {
        final List<Npc> made = MADE.remove(world.getUID());
        if (made == null)
            return;
        for (final Npc npc : made) {
            try {
                npc.removeForAll();
                FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
            } catch (final RuntimeException ignored) {
                // FancyNpcs is shutting down too: it won't save them, as they're not saved to file
            }
        }
    }

    static void removeAll() {
        for (final UUID id : new ArrayList<>(MADE.keySet())) {
            final World world = org.bukkit.Bukkit.getWorld(id);
            if (world != null)
                removeFrom(world);
        }
        MADE.clear();
    }
}
