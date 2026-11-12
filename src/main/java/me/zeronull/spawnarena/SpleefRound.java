package me.zeronull.spawnarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A free-for-all spleef round: everyone waiting in a spleef arena's free-for-all queue goes in together (see ArenaQueue),
 * each on a spot of their own spread over the top layer, everyone against everyone; the last one standing wins. It's an
 * ordinary match otherwise (countdown, the kit, their things put away and given back, the floor put back after): only
 * who's in it, when it's over and what it counts for differ.
 * <ul>
 *   <li>Out: as in any spleef match (the liquid at the bottom, leaving, quitting). The rest carry on, told who's left.</li>
 *   <li>Kills: whoever broke the block under someone, or hit them with a snowball, in the last {@link #CREDIT_MILLIS}
 *   before they went out, knocked them out (Spleef tells it through {@link #onHurt}).</li>
 *   <li>Counts: a win for the winner, a loss for everyone else; Elo by where each finished
 *   (StatsManager#recordPlacementElo); kills and knock-outs as the arena's kills and deaths. Not if the winner shares an IP
 *   with anyone in it (blockSameIpStats). No bets.</li>
 *   <li>Leaving during the countdown only takes them out of the round, while two are still in it.</li>
 * </ul>
 */
public final class SpleefRound extends Fight {
    /** How long breaking the floor under someone (or a snowball) counts as knocking them out. */
    private static final long CREDIT_MILLIS = 10_000;

    private final List<Player> members = new CopyOnWriteArrayList<>();
    /** victim -> who last undermined or hit them, and when */
    private record Hit(Player by, long at) {}
    private final Map<UUID, Hit> lastHit = new ConcurrentHashMap<>();
    /** In the order they went out (first out = last place) */
    private final List<Player> out = new CopyOnWriteArrayList<>();
    /** victim -> who knocked them out (absent: they went out on their own) */
    private final Map<UUID, Player> outBy = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();

    /** Starts a round in this arena with these players (2 or more). */
    public void start(final List<Player> players, final Arena arena) {
        this.members.addAll(players);
        this.initiateRound(players, arena);
        for (final Player p : players)
            if (p.isOnline())
                p.sendMessage(MiniMessage.miniMessage().deserialize("<green>Spleef free-for-all: <white><n></white> players. Last one standing wins!",
                        Placeholder.unparsed("n", String.valueOf(players.size()))));
    }

    // ── Everyone against everyone ───────────────────────────────────────────

    @Override
    public List<Player> getMembers() {
        return new ArrayList<>(this.members);
    }

    @Override
    public List<Player> getTeamOf(final Player player) {
        return this.members.contains(player) ? List.of(player) : List.of();
    }

    @Override
    public boolean areOpponents(final Player a, final Player b) {
        return !a.equals(b) && this.members.contains(a) && this.members.contains(b);
    }

    @Override
    public boolean areTeammates(final Player a, final Player b) {
        return false;
    }

    @Override
    public boolean onFirstTeam(final Player player) {
        return false;
    }

    @Override
    public void onHurt(final Player attacker, final Player victim) {
        if (attacker != null && !attacker.equals(victim))
            this.lastHit.put(victim.getUniqueId(), new Hit(attacker, System.currentTimeMillis()));
    }

    // ── Spawns spread over the top layer ────────────────────────────────────

    /** The snapshot as usual, then (on the arena's thread) a spot each, as far apart as they can be on the top layer. */
    @Override
    protected CompletableFuture<Boolean> prepareArena() {
        final Location anchor = this.arena.spawnPoint1;
        return super.prepareArena().thenCompose(ready -> !Boolean.TRUE.equals(ready) || anchor == null
                ? CompletableFuture.completedFuture(ready)
                : Threads.supplyAt(anchor, () -> {
                    this.placeEveryone();
                    return true;
                }));
    }

    private void placeEveryone() {
        final List<Location> spots = this.topLayerSpots();
        final List<Player> players = this.getMembers();
        if (spots.isEmpty()) {
            // No floor found (no region?): the arena's two spawns, in turn
            for (int i = 0; i < players.size(); i++)
                this.spawnSpots.put(players.get(i).getUniqueId(), (i % 2 == 0 ? this.arena.spawnPoint1 : this.arena.spawnPoint2).clone());
            return;
        }
        // Farthest first: each next spot as far as it can be from those taken (all used again if there are fewer spots)
        final List<Location> chosen = new ArrayList<>();
        final Random random = new Random();
        chosen.add(spots.get(random.nextInt(spots.size())));
        while (chosen.size() < players.size()) {
            Location best = null;
            double bestDistance = -1;
            for (final Location spot : spots) {
                double nearest = Double.MAX_VALUE;
                for (final Location taken : chosen)
                    nearest = Math.min(nearest, spot.distanceSquared(taken));
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = spot;
                }
            }
            chosen.add(best);
        }
        // Facing the middle of the floor
        double mx = 0, mz = 0;
        for (final Location spot : spots) {
            mx += spot.getX();
            mz += spot.getZ();
        }
        mx /= spots.size();
        mz /= spots.size();
        Collections.shuffle(chosen, random);
        for (int i = 0; i < players.size(); i++) {
            final Location at = chosen.get(i).clone();
            at.setYaw((float) Math.toDegrees(Math.atan2(-(mx - at.getX()), mz - at.getZ())));
            at.setPitch(0);
            this.spawnSpots.put(players.get(i).getUniqueId(), at);
        }
    }

    /** Where someone can stand on the highest floor layer: floor under them, room above, inside the arena. */
    private List<Location> topLayerSpots() {
        final World world = this.arena.spawnPoint1.getWorld();
        if (world == null || Arena.ArenaUtils.REGION_CONTAINER == null)
            return List.of();
        final com.sk89q.worldguard.protection.managers.RegionManager manager =
                Arena.ArenaUtils.REGION_CONTAINER.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
        final com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager == null ? null : manager.getRegion(this.arena.getArenaName());
        if (region == null)
            return List.of();
        final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        final Set<org.bukkit.Material> floor = this.arena.getSpleefFloor();
        for (int y = Math.min(max.y(), world.getMaxHeight() - 3); y >= Math.max(min.y(), world.getMinHeight()); y--) {
            final List<Location> spots = new ArrayList<>();
            for (int x = min.x(); x <= max.x(); x++)
                for (int z = min.z(); z <= max.z(); z++) {
                    final Block block = world.getBlockAt(x, y, z);
                    if (floor.contains(block.getType()) && block.getRelative(0, 1, 0).isPassable() && block.getRelative(0, 2, 0).isPassable()
                            && !block.getRelative(0, 1, 0).isLiquid())
                        spots.add(new Location(world, x + 0.5, y + 1, z + 0.5));
                }
            if (!spots.isEmpty())
                return spots; // the highest layer with any floor
        }
        return List.of();
    }

    // ── Going out ───────────────────────────────────────────────────────────

    @Override
    public void eliminate(final Player player) {
        synchronized (this) {
            if ((this.getState() == FightState.IN_FIGHT || this.getState() == FightState.PRE_FIGHT_START) && this.isFighter(player)
                    && !this.out.contains(player)) {
                this.out.add(player);
                final Hit hit = this.lastHit.remove(player.getUniqueId());
                if (hit != null && System.currentTimeMillis() - hit.at() <= CREDIT_MILLIS) {
                    this.outBy.put(player.getUniqueId(), hit.by());
                    this.kills.merge(hit.by().getUniqueId(), 1, Integer::sum);
                }
            }
        }
        super.eliminate(player);
    }

    @Override
    protected boolean overWithout(final List<Player> team) {
        return this.alive.size() <= 1;
    }

    @Override
    protected void finishWithout(final List<Player> team) {
        final List<Player> winners = this.getFighters();
        final List<Player> losers = this.getMembers();
        losers.removeAll(winners);
        this.finish(winners, losers);
    }

    @Override
    protected void outWhileOn(final Player player) {
        final int left = this.alive.size();
        final Player by = this.outBy.get(player.getUniqueId());
        final String text = by == null
                ? "<gray><name> fell out! <white><left></white> left."
                : "<gray><name> was knocked out by <by>! <white><left></white> left.";
        final Component message = MiniMessage.miniMessage().deserialize(text, Placeholder.unparsed("name", player.getName()),
                Placeholder.unparsed("by", by == null ? "" : by.getName()), Placeholder.unparsed("left", String.valueOf(left)));
        for (final Player p : this.getMembers())
            if (p.isOnline())
                p.sendMessage(message);
        if (player.isOnline())
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>You're out! You placed <white>#<place></white> of <n>.",
                    Placeholder.unparsed("place", String.valueOf(left + 1)), Placeholder.unparsed("n", String.valueOf(this.members.size()))));
    }

    /** A dropped connection: just out (there's no draw in a round, and no bets to give back). */
    @Override
    public void lagOut(final Player player) {
        this.eliminate(player);
    }

    @Override
    public void winForTeamOf(final Player player) {
        // (no pressure-plate wins in a round)
    }

    /** Leaving during the countdown only takes them out of the round, while at least two are still in it. */
    @Override
    public boolean cancelBecause(final Player culprit, final String messageForOthers) {
        synchronized (this) {
            if (this.getState() != FightState.COUNTDOWN)
                return false;
            if (this.members.contains(culprit) && this.members.size() > 2) {
                this.members.remove(culprit);
                this.alive.remove(culprit);
                for (final Player p : this.getMembers())
                    if (p.isOnline())
                        p.sendMessage(ChatColor.GRAY + culprit.getName() + " left the round. " + this.members.size() + " still in it.");
                return true;
            }
        }
        return super.cancelBecause(culprit, messageForOthers);
    }

    // ── The result ──────────────────────────────────────────────────────────

    @Override
    protected void finish(final List<Player> winners, final List<Player> losers) {
        // Placed: the winner, then the others in the order they outlasted the rest
        final List<Player> placed = new ArrayList<>(winners);
        for (int i = this.out.size() - 1; i >= 0; i--)
            if (!placed.contains(this.out.get(i)))
                placed.add(this.out.get(i));
        for (final Player p : this.members)
            if (!placed.contains(p))
                placed.add(p);
        final Map<UUID, java.net.InetAddress> addresses = new HashMap<>();
        for (final Player p : this.members)
            if (p.getAddress() != null)
                addresses.put(p.getUniqueId(), p.getAddress().getAddress());
        final Map<UUID, Integer> kills = Map.copyOf(this.kills);
        final Map<UUID, Player> outBy = Map.copyOf(this.outBy);
        final long seconds = this.startedAt() == 0 ? 0 : (System.currentTimeMillis() - this.startedAt()) / 1000;

        this.endFight();
        this.showTitles(placed);
        if (winners.isEmpty() || this.isDuel())
            return;
        Threads.result(() -> {
            try {
                this.record(winners.get(0), losers, placed, addresses, kills, outBy, seconds);
            } catch (final RuntimeException e) {
                SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE, "Couldn't record a spleef round in " + this.arena.getArenaName(), e);
            }
        });
    }

    private void showTitles(final List<Player> placed) {
        final net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(750));
        for (int i = 0; i < placed.size(); i++) {
            final Player p = placed.get(i);
            if (!p.isOnline())
                continue;
            p.showTitle(net.kyori.adventure.title.Title.title(
                    MiniMessage.miniMessage().deserialize(i == 0 ? "<green><bold>Victory!" : "<red><bold>#" + (i + 1)),
                    MiniMessage.miniMessage().deserialize(i == 0 ? "<gray>Last one standing of " + placed.size()
                            : "<gray>of " + placed.size() + " · " + placed.get(0).getName() + " won"), times));
        }
    }

    /** Off the main thread: stats, then the announcement, then the reward. */
    private void record(final Player winner, final List<Player> losers, final List<Player> placed, final Map<UUID, java.net.InetAddress> addresses,
                        final Map<UUID, Integer> kills, final Map<UUID, Player> outBy, final long seconds) {
        final StatsManager stats = SpawnArena.statsManager;
        final String arenaName = this.arena.getArenaName();
        boolean alts = false;
        if (SpawnArena.settings != null && (SpawnArena.settings.blockSameIpStats || SpawnArena.settings.blockSameIpRewards))
            for (final Player loser : losers)
                if (stats.areAlts(winner.getUniqueId(), addresses.get(winner.getUniqueId()), loser.getUniqueId(), addresses.get(loser.getUniqueId()))) {
                    alts = true;
                    break;
                }
        final boolean count = !(alts && SpawnArena.settings.blockSameIpStats);
        this.logMatch(List.of(winner), losers, count, count, false);

        int streak = 0;
        Map<UUID, int[]> elo = Map.of();
        if (count) {
            for (final Player loser : losers)
                stats.recordLoss(loser, arenaName);
            streak = stats.recordWin(winner, arenaName);
            stats.recordMatch(arenaName, seconds);
            elo = stats.recordPlacementElo(placed, arenaName);
            for (final Map.Entry<UUID, Integer> k : kills.entrySet())
                stats.recordFfaKill(k.getKey(), arenaName, k.getValue());
            for (final Player p : losers)
                stats.recordFfaDeath(p.getUniqueId(), arenaName);
            TopEloSuffix.refreshLater();
            if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms"))
                LeaderboardHolograms.refreshArena(arenaName);
        } else
            stats.setLastResult(winner.getUniqueId(), arenaName, "Not counted: same IP as another player");

        // The placings, and who knocked out whom, on hover
        final StringBuilder card = new StringBuilder("<gold>Spleef round, " + placed.size() + " players</gold>");
        for (int i = 0; i < placed.size(); i++) {
            final Player p = placed.get(i);
            final Player by = outBy.get(p.getUniqueId());
            final int k = kills.getOrDefault(p.getUniqueId(), 0);
            card.append("\n<gray>#").append(i + 1).append(" <white>").append(MiniMessage.miniMessage().escapeTags(p.getName())).append("</white>")
                    .append(k > 0 ? " <green>" + k + " kill" + (k == 1 ? "" : "s") + "</green>" : "")
                    .append(by != null ? " <dark_gray>(out to " + MiniMessage.miniMessage().escapeTags(by.getName()) + ")" : "");
        }
        final Component hover = MiniMessage.miniMessage().deserialize(card.toString());
        final Map<UUID, int[]> eloChanges = elo;
        final int winStreak = streak;
        final String arenaPart = "arena".equals(arenaName) ? "" : Arena.displayName(arenaName) + " ";
        Threads.callGlobal(() -> {
            for (final Player p : placed) {
                final int[] change = eloChanges.get(p.getUniqueId());
                if (change != null && p.isOnline())
                    p.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Elo: <white><elo></white> (<change>)",
                            Placeholder.unparsed("elo", String.valueOf(change[0])),
                            Placeholder.parsed("change", change[1] >= 0 ? "<green>+" + change[1] + "</green>" : "<red>" + change[1] + "</red>")));
            }
            broadcast(this.arena, MiniMessage.miniMessage().deserialize(
                    "<dark_red><winner> won a <n>-player spleef free-for-all in the <arena>arena<streak>!",
                    Placeholder.component("winner", winner.displayName()),
                    Placeholder.unparsed("n", String.valueOf(placed.size())),
                    Placeholder.parsed("arena", arenaPart),
                    Placeholder.parsed("streak", winStreak > 2 ? " with a win streak of <white>" + winStreak + "<dark_red>" : ""))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover)), List.of(winner), losers);
            return null;
        });
        if (Bukkit.getPluginManager().isPluginEnabled("Vault") && winner.isOnline())
            WinRewards.reward(winner, losers, this.arena, alts);
    }
}
