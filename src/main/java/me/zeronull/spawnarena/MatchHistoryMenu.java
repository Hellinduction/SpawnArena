package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /matches: your own recorded matches, to watch back ({@link ArenaReplay}). Near an arena you've played in, it opens
 * straight on that arena's matches; anywhere else, first a menu of the arenas you've played in, each opening its own
 * list. Lists are newest first, 45 to a page.
 */
public final class MatchHistoryMenu implements InventoryHolder {
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45, SLOT_FAVOURITES = 47, SLOT_ALL = 49, SLOT_NEXT = 53;
    /** How close to an arena counts as being at it */
    private static final double NEAR = 64;
    /** {@link #arena} for the list of their favourites */
    private static final String FAVOURITES = "\u0000favourites";

    /** Null: the menu of arenas; FAVOURITES: their favourites; otherwise that arena's matches */
    private final String arena;
    private final int page;
    private final Inventory inventory;
    /** Slot -> what clicking it opens */
    private final Map<Integer, Object> targets = new LinkedHashMap<>();

    private MatchHistoryMenu(final Player viewer, final String arena, final int page) {
        this.arena = arena;
        this.page = page;
        final java.util.UUID me = viewer.getUniqueId();
        final List<MatchIndex.Match> mine = MatchIndex.of(me, null);
        final List<MatchIndex.Match> favourites = favouritesOf(me, mine);
        if (arena == null) {
            // One item per arena they've played in, most recently played first; their favourites last
            final Map<String, List<MatchIndex.Match>> byArena = new LinkedHashMap<>();
            for (final MatchIndex.Match m : mine)
                byArena.computeIfAbsent(m.arena().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
            final int rows = Math.max(1, Math.min(6, (byArena.size() + 1 + 8) / 9));
            this.inventory = Bukkit.createInventory(this, rows * 9, mm("<dark_gray>Your Matches"));
            int slot = 0;
            for (final Map.Entry<String, List<MatchIndex.Match>> e : byArena.entrySet()) {
                if (slot >= rows * 9 - 1)
                    break;
                final List<MatchIndex.Match> list = e.getValue();
                int won = 0;
                for (final MatchIndex.Match m : list) {
                    final MatchIndex.Side side = m.side(me);
                    if (side != null && side.won())
                        won++;
                }
                final String name = list.get(0).arena();
                this.inventory.setItem(slot, item(icon(name), "<gold><bold>" + Arena.displayName(name), List.of(
                        "<gray>Matches recorded: <white>" + list.size(),
                        "<gray>Won <green>" + won + "</green> <gray>· Lost <red>" + (list.size() - won),
                        "<gray>Last played: <white>" + when(list.get(0).started()),
                        "",
                        "<green>Click to see them")));
                this.targets.put(slot, name);
                slot++;
            }
            this.inventory.setItem(rows * 9 - 1, favouritesButton(favourites.size()));
            this.targets.put(rows * 9 - 1, FAVOURITES);
            return;
        }
        // One arena's matches, or their favourites, a page at a time
        final boolean favList = FAVOURITES.equals(arena);
        final List<MatchIndex.Match> all = favList ? favourites : MatchIndex.of(me, arena);
        final int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        this.inventory = Bukkit.createInventory(this, 54, mm("<dark_gray>" + (favList ? "Favourite Matches" : Arena.displayName(arena) + " Matches")
                + (pages > 1 ? " <gray>(" + page + "/" + pages + ")" : "")));
        final int from = (page - 1) * PER_PAGE;
        for (int i = from; i < Math.min(all.size(), from + PER_PAGE); i++) {
            final MatchIndex.Match m = all.get(i);
            final MatchIndex.Side side = m.side(me);
            if (side == null)
                continue;
            final boolean favourite = MatchFavourites.is(me, m.file());
            final List<String> against = new ArrayList<>();
            for (final MatchIndex.Side s : m.against(side))
                against.add(s.name());
            final List<String> lore = new ArrayList<>();
            if (favourite)
                lore.add("<yellow>★ Favourite");
            if (favList)
                lore.add("<gray>" + Arena.displayName(m.arena()));
            lore.add("<gray>" + when(m.started()));
            lore.add("<gray>Length: <white>" + length(m.lengthMs()));
            final SumoVariant v = variant(m.variant());
            if (v != null && v != SumoVariant.CLASSIC)
                lore.add("<gray>Mode: <white>" + v.title);
            lore.add("<gray>" + (m.duel() ? "Duel" : "Queued"));
            lore.add("");
            lore.add("<green>Left-click to watch it back");
            lore.add("<yellow>Right-click to " + (favourite ? "unfavourite" : "favourite"));
            lore.add("<aqua>Shift-click for its command in chat");
            final String vs = against.isEmpty() ? "?" : Recording.opponents(against, m.freeForAll());
            final ItemStack item = item(side.won() ? Material.LIME_DYE : Material.RED_DYE,
                    (side.won() ? "<green><bold>Won</bold>" : "<red><bold>Lost</bold>") + " <gray>vs <white>" + esc(vs), lore);
            if (favourite)
                item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            this.inventory.setItem(i - from, item);
            this.targets.put(i - from, m);
        }
        if (all.isEmpty() && favList)
            this.inventory.setItem(22, item(Material.NETHER_STAR, "<gray>No favourites yet", List.of("<gray>Right-click a match in a list", "<gray>to favourite it.")));
        final ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int s = 45; s < 54; s++)
            this.inventory.setItem(s, filler);
        if (page > 1) {
            this.inventory.setItem(SLOT_PREV, item(Material.ARROW, "<gray>Previous page", List.of("<gray>Page " + (page - 1) + " of " + pages)));
            this.targets.put(SLOT_PREV, page - 1);
        }
        if (page < pages) {
            this.inventory.setItem(SLOT_NEXT, item(Material.ARROW, "<gray>Next page", List.of("<gray>Page " + (page + 1) + " of " + pages)));
            this.targets.put(SLOT_NEXT, page + 1);
        }
        if (!favList) {
            this.inventory.setItem(SLOT_FAVOURITES, favouritesButton(favourites.size()));
            this.targets.put(SLOT_FAVOURITES, FAVOURITES);
        }
        this.inventory.setItem(SLOT_ALL, item(Material.BOOK, "<gold>All your matches", List.of("<gray>Every arena you've played in")));
        this.targets.put(SLOT_ALL, Boolean.TRUE);
    }

    private static ItemStack favouritesButton(final int count) {
        final ItemStack star = item(Material.NETHER_STAR, "<yellow><bold>★ Favourites", List.of(
                "<gray>Matches you've favourited: <white>" + count, "<dark_gray>Favourites are never cleared out", "", "<green>Click to see them"));
        star.editMeta(meta -> meta.setEnchantmentGlintOverride(count > 0));
        return star;
    }

    /** Their favourites that are their own recorded matches still on disk, newest favourite first. */
    private static List<MatchIndex.Match> favouritesOf(final java.util.UUID player, final List<MatchIndex.Match> mine) {
        final Map<String, MatchIndex.Match> byKey = new java.util.HashMap<>();
        for (final MatchIndex.Match m : mine)
            byKey.put(MatchFavourites.key(m.file()), m);
        final List<MatchIndex.Match> out = new ArrayList<>();
        for (final String key : MatchFavourites.of(player)) {
            final MatchIndex.Match m = byKey.get(key);
            if (m != null)
                out.add(m);
        }
        return out;
    }

    /**
     * Opens /matches: on the arena they're at, if they've played there; otherwise the menu of arenas they've played in.
     * Returns why it can't open (nothing recorded yet), or null.
     */
    public static String open(final Player player) {
        if (!MatchIndex.ready())
            return "Your matches are still being looked up. Try again in a moment.";
        final List<MatchIndex.Match> all = MatchIndex.of(player.getUniqueId(), null);
        if (all.isEmpty())
            return "You don't have any recorded matches yet.";
        final String near = nearestArena(player);
        if (near != null && !MatchIndex.of(player.getUniqueId(), near).isEmpty())
            player.openInventory(new MatchHistoryMenu(player, near, 1).getInventory());
        else
            player.openInventory(new MatchHistoryMenu(player, null, 1).getInventory());
        return null;
    }

    /** The arena they're at (within 64 blocks of its middle), nearest first; null if none. */
    private static String nearestArena(final Player player) {
        final Location at = player.getLocation();
        String best = null;
        double bestD = NEAR * NEAR;
        for (final Arena arena : SpawnArena.arenas) {
            final Location a = arena.spawnPoint1, b = arena.spawnPoint2;
            if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(at.getWorld()))
                continue;
            final double x = (a.getX() + b.getX()) / 2 - at.getX(), y = (a.getY() + b.getY()) / 2 - at.getY(), z = (a.getZ() + b.getZ()) / 2 - at.getZ();
            final double d = x * x + y * y + z * z;
            if (d <= bestD) {
                bestD = d;
                best = arena.getArenaName();
            }
        }
        return best;
    }

    private static Material icon(final String arenaName) {
        final Arena arena = SpawnArena.arenas.of(arenaName);
        if (arena == null)
            return Material.PAPER;
        if (arena.isCrystalPvp())
            return Material.END_CRYSTAL;
        if (arena.isGiveKnockBackStick())
            return Material.TROPICAL_FISH;
        return Material.IRON_SWORD;
    }

    private static SumoVariant variant(final String name) {
        try {
            return name == null || name.isEmpty() ? null : SumoVariant.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String when(final long ms) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(new Date(ms));
    }

    private static String length(final int ms) {
        final int s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    private static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(mm(name));
        final List<Component> lines = new ArrayList<>();
        for (final String line : lore)
            lines.add(mm(line));
        meta.lore(lines);
        meta.setAttributeModifiers(com.google.common.collect.ImmutableMultimap.of());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        QueueMenu.markButton(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static Component mm(final String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private static String esc(final String s) {
        return MiniMessage.miniMessage().escapeTags(s);
    }

    /** Clicks: nothing can be taken out; an arena opens its list, a match starts its replay, the arrows turn pages. */
    public static final class Clicks implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(final InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof MatchHistoryMenu menu))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getView().getTopInventory())
                return;
            final Object target = menu.targets.get(e.getSlot());
            if (target == null)
                return;
            final boolean shift = e.isShiftClick(), right = e.isRightClick();
            Threads.entityLater(player, () -> {
                if (!player.isOnline())
                    return;
                if (target instanceof String arena)
                    player.openInventory(new MatchHistoryMenu(player, arena, 1).getInventory());
                else if (target instanceof Integer page)
                    player.openInventory(new MatchHistoryMenu(player, menu.arena, page).getInventory());
                else if (target == Boolean.TRUE)
                    player.openInventory(new MatchHistoryMenu(player, null, 1).getInventory());
                else if (target instanceof MatchIndex.Match m) {
                    if (shift)
                        commandInChat(player, m);
                    else if (right) {
                        final Boolean now = MatchFavourites.toggle(player.getUniqueId(), m.file());
                        if (now == null)
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>You can have " + MatchFavourites.MAX + " favourites at most: unfavourite one first."));
                        player.openInventory(new MatchHistoryMenu(player, menu.arena, menu.page).getInventory());
                    } else
                        watch(player, m);
                }
            }, 1L);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(final InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof MatchHistoryMenu)
                e.setCancelled(true);
        }

        /** When each player was last hurt by another player (a replay can't be used to get away from a fight). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(final org.bukkit.event.entity.EntityDamageByEntityEvent e) {
            if (!(e.getEntity() instanceof Player hurt))
                return;
            final org.bukkit.entity.Entity by = e.getDamager() instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof org.bukkit.entity.Entity s ? s : e.getDamager();
            if (by instanceof Player && by != hurt)
                LAST_HURT.put(hurt.getUniqueId(), System.currentTimeMillis());
        }

        @EventHandler
        public void onQuit(final org.bukkit.event.player.PlayerQuitEvent e) {
            LAST_HURT.remove(e.getPlayer().getUniqueId());
        }
    }

    /** Player -> when another player last hurt them */
    private static final Map<java.util.UUID, Long> LAST_HURT = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long after being hurt by a player a replay can't be started (it may take them to the arena) */
    private static final long COMBAT_MS = 15_000;

    /**
     * Why they can't watch a replay in {@code arenaName} right now, or null: not in a match or a queue, not just hurt by
     * a player, and (unless they can watch anyone's: spawnarena.arenareplay.others) already in the arena's world, so the
     * trip there and back stays where they are.
     */
    public static String cantWatch(final Player player, final String arenaName) {
        if (SpawnArena.arenas.of(player) != null || SpawnArena.arenas.isInQueue(player))
            return "You can't watch a replay while you're in a match or a queue.";
        final Long hurt = LAST_HURT.get(player.getUniqueId());
        if (hurt != null && System.currentTimeMillis() - hurt < COMBAT_MS)
            return "You can't watch a replay right after a fight. Try again in a few seconds.";
        if (!player.hasPermission("spawnarena.arenareplay.others")) {
            final Arena arena = SpawnArena.arenas.of(arenaName);
            if (arena != null && arena.spawnPoint1 != null && arena.spawnPoint1.getWorld() != null && !arena.spawnPoint1.getWorld().equals(player.getWorld()))
                return "Go to spawn to watch your replays.";
        }
        return null;
    }

    /** Sends the command that plays it: click to copy it, or to put it in their chat box. */
    public static void commandInChat(final Player player, final MatchIndex.Match m) {
        player.closeInventory();
        sendCommand(player, m.file(), true);
    }

    /**
     * "[Copy] [Put in chat]" for /arenareplay file of this recording, and for one of their own matches a favourite
     * button too.
     */
    public static void sendCommand(final Player player, final java.io.File recording, final boolean theirs) {
        final String key = MatchFavourites.key(recording);
        final String cmd = "/arenareplay file " + key;
        if (theirs) {
            final boolean fav = MatchFavourites.is(player.getUniqueId(), recording);
            player.sendMessage(MiniMessage.miniMessage().deserialize("<click:run_command:'/matches favourite " + key + "'><hover:show_text:'<gray>"
                    + (fav ? "Take it off your favourites" : "Keep it in your favourites (/matches)") + "'>"
                    + (fav ? "<yellow>[★ Favourited]</yellow>" : "<yellow>[☆ Favourite]</yellow>") + "</hover></click>"));
        }
        // (the path is only letters, digits, _ . - and /: nothing MiniMessage reads as a tag)
        player.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Command to watch it: <white>" + cmd + "</white> "
                + "<click:copy_to_clipboard:'" + cmd + "'><hover:show_text:'<gray>Copy it'><aqua>[Copy]</aqua></hover></click> "
                + "<click:suggest_command:'" + cmd + "'><hover:show_text:'<gray>Put it in your chat box'><aqua>[Put in chat]</aqua></hover></click>"));
    }

    /** Starts watching it, unless they're in a match or a queue. */
    private static void watch(final Player player, final MatchIndex.Match m) {
        player.closeInventory();
        final String problem = cantWatch(player, m.arena());
        if (problem != null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + esc(problem)));
            return;
        }
        if (!m.file().isFile()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>That recording isn't there any more."));
            return;
        }
        Threads.async(() -> {
            final Recording rec;
            try {
                rec = Recording.read(m.file());
            } catch (final java.io.IOException ex) {
                Threads.entity(player, () -> player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Couldn't read that recording.")));
                return;
            }
            Threads.entity(player, () -> ArenaReplay.start(player, rec));
        });
    }
}
