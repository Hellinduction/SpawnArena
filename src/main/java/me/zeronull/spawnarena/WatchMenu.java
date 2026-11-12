package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /arenawatch &lt;arena&gt; (for an NPC to run): a MULTI crystal arena's viewing arena, or one of its matches to watch up
 * close in its copy. Whoever watches a match there is brought back to the viewing arena when it ends.
 */
public final class WatchMenu implements InventoryHolder {
    private static final int VIEWING_SLOT = 10;
    /** This close to the real arena (in its world), someone is already at the viewing arena. */
    private static final double ALREADY_THERE_BLOCKS = 64;
    private static final int[] MATCH_SLOTS = {12, 13, 14, 15, 16, 21, 22, 23};

    /** Player watching a match in its copy -> that copy */
    private static final Map<UUID, CrystalCopy> WATCHING = new ConcurrentHashMap<>();
    /** Player sent out of a copy's void -> when (falling keeps firing until the teleport is done) */
    private static final Map<UUID, Long> RESCUED = new ConcurrentHashMap<>();
    /** Player watching a match in a copy -> where they were before (outside the copies), to go back to when it ends */
    private static final Map<UUID, Location> CAME_FROM = new ConcurrentHashMap<>();

    private final String arenaName;
    private final Inventory inventory;
    /** Slot -> the copy whose match it shows */
    private final Map<Integer, CrystalCopy> matches = new HashMap<>();

    private WatchMenu(final Arena arena) {
        this.arenaName = arena.getArenaName();
        this.inventory = Bukkit.createInventory(this, 36, mm("<dark_gray>Watch " + Arena.displayName(arena.getArenaName())));
        this.fill(arena);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    private void fill(final Arena arena) {
        final ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < this.inventory.getSize(); i++)
            this.inventory.setItem(i, filler);
        this.matches.clear();

        final List<CrystalCopy> live = new ArrayList<>();
        for (final CrystalCopy copy : CrystalCopies.of(arena))
            if (copy.getFights().stream().anyMatch(f -> f.getState() == FightState.IN_FIGHT || f.getState() == FightState.PRE_FIGHT_START))
                live.add(copy);

        this.inventory.setItem(VIEWING_SLOT, item(Material.ENDER_EYE, "<aqua><bold>Viewing Arena</bold>", List.of(
                "<gray>Every match at once,", "<gray>from the deck.", "",
                "<gray>Matches on: <white>" + live.size(), "", "<green>Click to go")));

        if (live.isEmpty()) {
            this.inventory.setItem(MATCH_SLOTS[0] + 1, item(Material.BARRIER, "<gray>No matches right now", List.of(
                    "<dark_gray>Matches show up here", "<dark_gray>as soon as they start.")));
            return;
        }
        for (int i = 0; i < live.size() && i < MATCH_SLOTS.length; i++) {
            final CrystalCopy copy = live.get(i);
            final Fight fight = copy.getFights().get(0);
            final List<Player> members = fight.getMembers();
            final List<String> names = new ArrayList<>();
            for (final Player p : members)
                names.add(fight.isFighter(p) ? "<white>" + p.getName() + "</white>" : "<dark_gray><strikethrough>" + p.getName() + "</strikethrough></dark_gray>");
            final ItemStack head = item(Material.PLAYER_HEAD, "<gold><bold>Match " + copy.index() + "</bold>", List.of(
                    String.join(" <gray>vs</gray> ", names),
                    "<gray>" + fight.getMode().label(), "",
                    "<green>Click to watch up close"));
            if (!members.isEmpty() && head.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(members.get(0));
                head.setItemMeta(skull);
            }
            this.inventory.setItem(MATCH_SLOTS[i], head);
            this.matches.put(MATCH_SLOTS[i], copy);
        }
    }

    private static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(mm(name));
        final List<Component> lines = new ArrayList<>();
        for (final String line : lore)
            lines.add(mm(line));
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        QueueMenu.markButton(meta); // one that somehow leaves the menu is taken away again
        item.setItemMeta(meta);
        return item;
    }

    private static Component mm(final String text) {
        return MiniMessage.miniMessage().deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** A spot to send a watcher back to: still there (its world loaded) and not in a copy. */
    private static boolean usable(final Location at) {
        return at != null && at.getWorld() != null && Bukkit.getWorld(at.getWorld().getUID()) != null && CrystalCopies.at(at.getWorld()) == null;
    }

    /** Where watchers stand: the arena's exit (its deck), in the real arena or in a copy. */
    private static Location deck(final SavableArena real, final CrystalCopy copy) {
        final Location exit = real.exitLocation != null ? real.exitLocation : real.spawnPoint1;
        return copy == null ? exit : copy.here(exit);
    }

    // ── The command ─────────────────────────────────────────────────────────

    public static final class WatchCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("You must be a player to run this command!");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "/" + label + " <arena>");
                return true;
            }
            final Arena arena = SpawnArena.arenas.of(args[0]);
            if (arena == null) {
                player.sendMessage(ChatColor.RED + "No arena called '" + args[0] + "'.");
                return true;
            }
            if (!(arena instanceof SavableArena real) || CrystalCopies.of(real).isEmpty()) {
                player.sendMessage(ChatColor.RED + "That arena's matches can't be watched this way (it isn't a MULTI crystal or spleef arena).");
                return true;
            }
            if (SpawnArena.arenas.hasFighter(player)) {
                player.sendMessage(ChatColor.RED + "You can't do that while fighting.");
                return true;
            }
            // As /joinarenaqueue: by the arena (in its world, within queueMaxDistance), or in one of its copies
            final CrystalCopy in = CrystalCopies.at(player.getWorld());
            final double range = SpawnArena.settings == null ? 64 : SpawnArena.settings.queueMaxDistance;
            if ((in == null || !in.real().equals(real)) && range > 0 && real.distanceFrom(player.getLocation()) > range) {
                player.sendMessage(ChatColor.RED + "You are too far from that arena!");
                return true;
            }
            player.openInventory(new WatchMenu(real).getInventory());
            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, final String[] args) {
            if (args.length != 1 || SpawnArena.arenas == null)
                return List.of();
            final List<String> out = new ArrayList<>();
            for (final Arena arena : SpawnArena.arenas)
                if (!CrystalCopies.of(arena).isEmpty() && arena.getArenaName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    out.add(arena.getArenaName());
            return out;
        }
    }

    // ── Clicks, keeping it current, and bringing watchers back ──────────────

    public static final class Events implements Listener {
        public Events() {
            Threads.globalTimer(task -> {
                // Open menus show the matches as they are now (each looked at on its player's thread: Folia; checked here
                // first on Paper, where that's this thread)
                for (final Player p : Bukkit.getOnlinePlayers())
                    if (Threads.FOLIA || p.getOpenInventory().getTopInventory().getHolder() instanceof WatchMenu)
                        Threads.entity(p, () -> {
                            if (p.getOpenInventory().getTopInventory().getHolder() instanceof WatchMenu menu) {
                                final Arena arena = SpawnArena.arenas.of(menu.arenaName);
                                if (arena != null)
                                    menu.fill(arena);
                            }
                        });
                // A match that's over: whoever was watching it goes back to the viewing arena
                for (final Map.Entry<UUID, CrystalCopy> e : new ArrayList<>(WATCHING.entrySet())) {
                    final Player p = Bukkit.getPlayer(e.getKey());
                    final CrystalCopy copy = e.getValue();
                    if (p == null || !p.getWorld().equals(copy.world())) {
                        WATCHING.remove(e.getKey()); // left by themselves
                        CAME_FROM.remove(e.getKey());
                        continue;
                    }
                    final boolean over = copy.getFights().stream().noneMatch(f -> f.getState() == FightState.IN_FIGHT
                            || f.getState() == FightState.PRE_FIGHT_START || f.getState() == FightState.COUNTDOWN);
                    if (over && !SpawnArena.arenas.hasFighter(p)) {
                        WATCHING.remove(e.getKey());
                        final Location from = CAME_FROM.remove(e.getKey());
                        Threads.teleport(p, usable(from) ? from : deck(copy.real(), null));
                        p.sendMessage(mm(usable(from) ? "<gray>That match is over: back to where you were." : "<gray>That match is over: back to the viewing arena."));
                    }
                }
            }, 20L, 20L);
        }

        @EventHandler
        public void onClick(final InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof WatchMenu menu))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getView().getTopInventory())
                return;
            final Arena arena = SpawnArena.arenas.of(menu.arenaName);
            if (!(arena instanceof SavableArena real))
                return;
            if (e.getSlot() == VIEWING_SLOT) {
                closeLater(player);
                // Already there (in the real arena's world, by the arena): nothing to do
                if (CrystalCopies.at(player.getWorld()) == null && real.distanceFrom(player.getLocation()) <= ALREADY_THERE_BLOCKS) {
                    player.sendMessage(mm("<gray>You're already at the viewing arena."));
                    return;
                }
                // Watching a match: back to where they were before it, as when it ends
                final Location from = CrystalCopies.at(player.getWorld()) != null ? CAME_FROM.get(player.getUniqueId()) : null;
                WATCHING.remove(player.getUniqueId());
                CAME_FROM.remove(player.getUniqueId());
                Threads.teleport(player, usable(from) ? from : deck(real, null));
                return;
            }
            final CrystalCopy copy = menu.matches.get(e.getSlot());
            if (copy == null)
                return;
            closeLater(player);
            if (player.getWorld().equals(copy.world())) {
                player.sendMessage(mm("<gray>You're already watching <white>match " + copy.index() + "<gray>."));
                return;
            }
            if (copy.getFights().isEmpty()) {
                player.sendMessage(mm("<red>That match just ended."));
                return;
            }
            WATCHING.put(player.getUniqueId(), copy);
            // From one match to another: still back to where they were before the first
            if (CrystalCopies.at(player.getWorld()) == null)
                CAME_FROM.put(player.getUniqueId(), player.getLocation());
            Threads.teleport(player, sameSpot(real, copy, player.getLocation()));
            player.sendMessage(mm("<gray>Watching <white>match " + copy.index() + "<gray>. You'll be back where you were when it ends."));
        }

        /**
         * Where someone picking a match lands in its copy: the same spot they're standing on, if they're at the viewing
         * arena (or watching another match) and not in its fighting space; otherwise its deck.
         */
        private static Location sameSpot(final SavableArena real, final CrystalCopy copy, final Location at) {
            final boolean inCopy = CrystalCopies.at(at.getWorld()) != null;
            final Location onSpawn = at.clone();
            if (inCopy)
                onSpawn.setWorld(real.spawnPoint1.getWorld());
            final boolean byArena = (inCopy || at.getWorld().equals(real.spawnPoint1.getWorld()))
                    && real.distanceFrom(onSpawn) <= ALREADY_THERE_BLOCKS;
            if (!byArena || real.isInFightingSpace(onSpawn))
                return deck(real, copy);
            return copy.here(onSpawn);
        }

        /**
         * Closes the menu a tick later: closing it inside the click event, before the server has undone the cancelled
         * click on the player's screen, can leave the clicked button in their inventory.
         */
        private static void closeLater(final Player player) {
            Threads.entityLater(player, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof WatchMenu)
                    player.closeInventory();
                QueueMenu.purge(player);
            }, 1L);
        }

        /**
         * Nothing stops someone watching in a copy walking off its edge: falling into the void there (or taking void
         * damage) sends them to that copy's deck instead. Not its fighters: falling out is part of their match.
         */
        @EventHandler(ignoreCancelled = true)
        public void onFallOut(final org.bukkit.event.player.PlayerMoveEvent e) {
            if (e.getTo().getY() >= e.getTo().getWorld().getMinHeight() - 8 || e.getFrom().getBlockY() == e.getTo().getBlockY())
                return;
            rescue(e.getPlayer());
        }

        @EventHandler(ignoreCancelled = true)
        public void onVoid(final org.bukkit.event.entity.EntityDamageEvent e) {
            if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID && e.getEntity() instanceof Player p && rescue(p))
                e.setCancelled(true);
        }

        private static boolean rescue(final Player player) {
            final CrystalCopy copy = CrystalCopies.at(player.getWorld());
            if (copy == null || SpawnArena.arenas.hasFighter(player))
                return false;
            final long now = System.currentTimeMillis();
            final Long last = RESCUED.put(player.getUniqueId(), now);
            if (last != null && now - last < 1_000)
                return true; // on their way already
            player.setFallDistance(0);
            player.setVelocity(new org.bukkit.util.Vector());
            Threads.teleport(player, deck(copy.real(), copy));
            return true;
        }

        /** Again after every other plugin: nothing may take this menu's click back off cancelled. */
        @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
        public void onClickLast(final InventoryClickEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof WatchMenu)
                e.setCancelled(true);
        }

        /** Whatever happened while it was open, none of its buttons leave with them. */
        @EventHandler
        public void onClose(final org.bukkit.event.inventory.InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof WatchMenu && e.getPlayer() instanceof Player p)
                Threads.entityLater(p, () -> QueueMenu.purge(p), 1L);
        }

        @EventHandler
        public void onDrag(final InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof WatchMenu)
                e.setCancelled(true);
        }
    }

    /** On shutdown: everyone watching in a copy goes back to where they were, or the viewing arena (the copies' worlds stay, empty). */
    public static void sendEveryoneBack() {
        // (who's in which world read from the players themselves: a world's own list is its region's alone on Folia)
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final CrystalCopy copy = CrystalCopies.at(p.getWorld());
            if (copy != null && !SpawnArena.arenas.hasFighter(p)) {
                final Location from = CAME_FROM.get(p.getUniqueId());
                Threads.teleport(p, usable(from) ? from : deck(copy.real(), null));
            }
        }
        WATCHING.clear();
        CAME_FROM.clear();
    }
}
