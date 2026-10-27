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
    private static final int[] MATCH_SLOTS = {12, 13, 14, 15, 16, 21, 22, 23};

    /** Player watching a match in its copy -> that copy */
    private static final Map<UUID, CrystalCopy> WATCHING = new ConcurrentHashMap<>();

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
                names.add((fight.isFighter(p) ? "<white>" : "<dark_gray><strikethrough>") + p.getName() + "</strikethrough>");
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
        item.setItemMeta(meta);
        return item;
    }

    private static Component mm(final String text) {
        return MiniMessage.miniMessage().deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
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
            if (!(arena instanceof SavableArena real) || CrystalCopies.of(real).isEmpty()) {
                player.sendMessage(ChatColor.RED + "That arena's matches can't be watched this way (it isn't a MULTI crystal arena).");
                return true;
            }
            if (SpawnArena.arenas.hasFighter(player)) {
                player.sendMessage(ChatColor.RED + "You can't do that while fighting.");
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
                // Open menus show the matches as they are now
                for (final Player p : Bukkit.getOnlinePlayers())
                    if (p.getOpenInventory().getTopInventory().getHolder() instanceof WatchMenu menu) {
                        final Arena arena = SpawnArena.arenas.of(menu.arenaName);
                        if (arena != null)
                            menu.fill(arena);
                    }
                // A match that's over: whoever was watching it goes back to the viewing arena
                for (final Map.Entry<UUID, CrystalCopy> e : new ArrayList<>(WATCHING.entrySet())) {
                    final Player p = Bukkit.getPlayer(e.getKey());
                    final CrystalCopy copy = e.getValue();
                    if (p == null || !p.getWorld().equals(copy.world())) {
                        WATCHING.remove(e.getKey()); // left by themselves
                        continue;
                    }
                    final boolean over = copy.getFights().stream().noneMatch(f -> f.getState() == FightState.IN_FIGHT
                            || f.getState() == FightState.PRE_FIGHT_START || f.getState() == FightState.COUNTDOWN);
                    if (over && !SpawnArena.arenas.hasFighter(p)) {
                        WATCHING.remove(e.getKey());
                        Threads.teleport(p, deck(copy.real(), null));
                        p.sendMessage(mm("<gray>That match is over: back to the viewing arena."));
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
                player.closeInventory();
                WATCHING.remove(player.getUniqueId());
                Threads.teleport(player, deck(real, null));
                return;
            }
            final CrystalCopy copy = menu.matches.get(e.getSlot());
            if (copy == null)
                return;
            player.closeInventory();
            if (copy.getFights().isEmpty()) {
                player.sendMessage(mm("<red>That match just ended."));
                return;
            }
            WATCHING.put(player.getUniqueId(), copy);
            Threads.teleport(player, deck(real, copy));
            player.sendMessage(mm("<gray>Watching <white>match " + copy.index() + "<gray>. You'll be back in the viewing arena when it ends."));
        }

        @EventHandler
        public void onDrag(final InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof WatchMenu)
                e.setCancelled(true);
        }
    }

    /** On shutdown: everyone watching in a copy goes back to the viewing arena (the copies' worlds stay, empty). */
    public static void sendEveryoneBack() {
        for (final CrystalCopy copy : CrystalCopies.all())
            for (final Player p : copy.world().getPlayers())
                if (!SpawnArena.arenas.hasFighter(p))
                    p.teleport(deck(copy.real(), null));
        WATCHING.clear();
    }
}
