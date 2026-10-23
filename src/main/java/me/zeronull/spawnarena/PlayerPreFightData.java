package me.zeronull.spawnarena;

import me.zeronull.spawnarena.config.ConfigHandler;
import me.zeronull.spawnarena.config.impl.PreFightConfig;
import me.zeronull.spawnarena.events.ArenaPlayerConsumeEvent;
import me.zeronull.spawnarena.inventory.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerPreFightData {
    public Player player;

    protected UUID uuid;
    protected String username;
    protected ItemStack[] contents;
    protected ItemStack[] armorContents;
    protected int level;
    protected float exp;
    protected Location previousLocation;
    protected GameMode previousGameMode;
    protected double health;
    protected int foodLevel;
    /** The hotbar slot they were holding (put back afterwards; the fight may switch them to its own item). */
    protected int heldSlot;
    protected List<PotionEffect> effects;
    /** Items that belong to the player but didn't fit in their inventory when the fight started (e.g. a recalled trident). */
    // Copy-on-write: added to on the player's thread while a save on another thread may be serializing it
    protected List<ItemStack> extraItems = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PlayerPreFightData(Player player) {
        this.player = player;
        this.username = this.player.getName();
        // Their own things on the cursor or in the crafting grid are kept with the rest (given back after the fight):
        // whatever is in either when it ends is the fight's, and is thrown away
        final ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            this.extraItems.add(cursor.clone());
            player.setItemOnCursor(null);
        }
        if (player.getOpenInventory().getTopInventory() instanceof org.bukkit.inventory.CraftingInventory grid
                && grid.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (final ItemStack item : grid.getMatrix())
                if (item != null && !item.getType().isAir())
                    this.extraItems.add(item.clone());
            grid.clear();
        }
        contents = cloneItemStackArray(player.getInventory().getContents());
        armorContents = cloneItemStackArray(player.getInventory().getArmorContents());
        level = player.getLevel();
        exp = player.getExp();
        previousLocation = player.getLocation();
        previousGameMode = player.getGameMode();
        health = player.getHealth();
        foodLevel = player.getFoodLevel();
        heldSlot = player.getInventory().getHeldItemSlot();
        effects = new ArrayList<>(player.getActivePotionEffects());

        this.uuid = player.getUniqueId();

        final ConfigHandler handler = ConfigHandler.getInstance();
        synchronized (handler) { // one change-save at a time (players get ready on their own threads on Folia)
            final PreFightConfig config = handler.getPreFightConfig();
            config.map.put(player.getUniqueId(), this);
            handler.savePreFightConfig(config);
        }
    }

    /**
     * Deserialize PlayerPreFightData from a JSONObject
     *
     * @param obj
     * @throws IOException
     */
    public PlayerPreFightData(final JSONObject obj) throws Exception {
        this.uuid = UUID.fromString(obj.getString("uuid"));
        this.player = Bukkit.getPlayer(this.uuid); // null if they're offline: their entry must still save fine
        this.username = obj.getString("username");
        this.contents = ItemSerializer.deserialize(obj.getString("contents"));
        this.armorContents = ItemSerializer.deserialize(obj.getString("armor_contents"));
        this.level = obj.getInt("level");
        this.exp = obj.getFloat("exp");
        this.previousLocation = LocationUtils.locationFromJson(new JSONObject(obj.getString("previous_location")));
        this.previousGameMode = GameMode.valueOf(obj.getString("previous_gamemode"));
        this.health = obj.getDouble("health");
        this.foodLevel = obj.getInt("food_level");
        this.heldSlot = obj.optInt("held_slot", -1); // saves from older versions don't have it
        if (obj.has("extra_items")) {
            for (final ItemStack item : ItemSerializer.deserialize(obj.getString("extra_items")))
                if (item != null)
                    this.extraItems.add(item);
        }
    }

    /** Holds one item to hand back at restore, without a message (the caller says why). */
    public void keepForAfter(final ItemStack item) {
        this.extraItems.add(item.clone());
        ConfigHandler.getInstance().register(this.player.getUniqueId(), this);
    }

    /** Holds items to hand back at restore (and saves them, so /restore has them too). */
    public void addExtraItems(final List<ItemStack> items) {
        if (items.isEmpty())
            return;
        for (final ItemStack item : items) {
            this.extraItems.add(item.clone());
            this.player.sendMessage(org.bukkit.ChatColor.RED + "Your thrown " + describe(item)
                    + " didn't fit in your inventory. You'll get it back after the fight.");
        }
        ConfigHandler.getInstance().register(this.player.getUniqueId(), this);
    }

    public void restore() {
        if (this.player == null || !this.player.isOnline())
            throw new IllegalStateException(String.format("Failed to restore PlayerPreFightData for %s because they were offline", this.username));

        ArenaPlayerConsumeEvent.PLAYER_FINISH_GAME_MAP.put(this.player.getUniqueId(), Instant.now().getEpochSecond());

        // Whatever is on their cursor or in a crafting grid (theirs, or a crafting table they placed in a crystal fight)
        // now came from the fight (both were emptied into their inventory before it was saved): closing the inventory
        // would hand it back to them, or drop it if they're full
        this.player.setItemOnCursor(null);
        if (this.player.getOpenInventory().getTopInventory() instanceof org.bukkit.inventory.CraftingInventory grid)
            grid.clear();
        this.player.closeInventory();

        // Queued from inside an arena? Don't send them back into it (another fight may be running there)
        Location destination = this.previousLocation;
        if (destination != null && Arena.ArenaUtils.WORLD_EDIT_SUPPORT) {
            final Arena inside = SpawnArena.arenas.of(destination);
            if (inside != null)
                destination = inside.exitFor(this.player);
        }
        FoliaUtils.teleport(this.player, destination);

        // These 2 just assume they were at this before
        this.player.setFireTicks(0);

        // Effects first, so a health boost they had raises max health before health is set
        this.restorePotionEffects();

        // setHealth throws above max health (e.g. a health boost that has since worn off)
        final org.bukkit.attribute.AttributeInstance maxHealth = this.player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double max = maxHealth == null ? 20 : maxHealth.getValue();
        this.player.setHealth(Math.max(0.5, Math.min(this.health, max)));
        this.player.setFoodLevel(this.foodLevel);
        if (this.heldSlot >= 0 && this.heldSlot <= 8)
            this.player.getInventory().setHeldItemSlot(this.heldSlot);
        this.player.setLevel(this.level);
        this.player.setExp(this.exp);
        this.player.getInventory().setContents(this.contents);
        this.player.getInventory().setArmorContents(this.armorContents);
        this.player.setGameMode(this.previousGameMode);

        // Items that didn't fit when the fight started: inventory first, then ender chest, then the ground
        if (!this.extraItems.isEmpty()) {
            for (final ItemStack item : this.extraItems)
                this.returnExtraItem(item, destination);
            this.extraItems.clear();
        }

        final ConfigHandler handler = ConfigHandler.getInstance();
        synchronized (handler) {
            final PreFightConfig config = handler.getPreFightConfig();
            config.map.remove(this.uuid);
            handler.savePreFightConfig(config);
        }
    }

    private void returnExtraItem(final ItemStack item, final Location dropAt) {
        final String name = describe(item);

        // Only what didn't fit moves on to the next place, so a stack that partly fits isn't duplicated
        ItemStack left = addInto(this.player.getInventory(), item);
        if (left == null) {
            this.player.sendMessage(org.bukkit.ChatColor.RED + "Your " + name + " was returned to your inventory.");
            return;
        }
        left = addInto(this.player.getEnderChest(), left);
        if (left == null) {
            this.player.sendMessage(org.bukkit.ChatColor.RED + "Your inventory was full, so (some of) your " + name + " went in your ender chest.");
            return;
        }
        // Logging out: dropping it would leave it on the ground for anyone, so hand it over next time they join
        if (QUITTING.contains(this.player.getUniqueId())) {
            stashForNextJoin(this.player, left);
            return;
        }
        final Location at = dropAt != null ? dropAt : this.player.getLocation();
        final ItemStack drop = left;
        Threads.location(at, () -> at.getWorld().dropItemNaturally(at, drop)); // that spot may be another thread's (Folia)
        this.player.sendMessage(org.bukkit.ChatColor.RED + "Your inventory and ender chest were full, so (some of) your " + name + " was dropped at your feet.");
    }

    /** Adds as much as fits; returns what's left over, or null if it all fitted. */
    private static ItemStack addInto(final org.bukkit.inventory.Inventory inventory, final ItemStack item) {
        final java.util.Map<Integer, ItemStack> leftover = inventory.addItem(item.clone());
        return leftover.isEmpty() ? null : leftover.values().iterator().next();
    }

    // ── Items owed to players who logged out ────────────────────────────────

    /** Players whose quit is being handled right now (see ArenaPlayerLeave). */
    public static final java.util.Set<UUID> QUITTING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Saved in the database (survives restarts, shared across servers on MySQL) and handed back when they join. */
    private static void stashForNextJoin(final Player player, final ItemStack item) {
        SpawnArena.statsManager.addOwedItem(player.getUniqueId(), item.serializeAsBytes());
        SpawnArena.INSTANCE.getLogger().info("Saved " + describe(item) + " owed to " + player.getName()
                + " (logged out with a full inventory and ender chest); it'll be given back when they join.");
    }

    /** On join (off the main thread): fetches what they're owed, then hands it over on their own thread. */
    public static void giveOwedItemsAsync(final Player player) {
        final UUID uuid = player.getUniqueId();
        org.bukkit.Bukkit.getAsyncScheduler().runNow(SpawnArena.INSTANCE, task -> {
            final List<byte[]> raw = SpawnArena.statsManager.takeOwedItems(uuid);
            if (raw.isEmpty())
                return;
            final List<ItemStack> owed = new ArrayList<>();
            for (final byte[] bytes : raw) {
                try {
                    owed.add(ItemStack.deserializeBytes(bytes));
                } catch (final Exception e) {
                    SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE, "Couldn't read an item owed to " + uuid, e);
                }
            }
            Threads.entity(player, () -> giveOwedItems(player, owed));
        });
    }

    /** Inventory, then ender chest, then their feet. If they've left again already, it's saved for next time. */
    private static void giveOwedItems(final Player player, final List<ItemStack> owed) {
        if (!player.isOnline()) {
            for (final ItemStack item : owed)
                SpawnArena.statsManager.addOwedItem(player.getUniqueId(), item.serializeAsBytes());
            return;
        }
        for (final ItemStack item : owed) {
            if (item == null || item.getType().isAir())
                continue;
            final String name = describe(item);
            ItemStack left = addInto(player.getInventory(), item);
            if (left == null) {
                player.sendMessage(org.bukkit.ChatColor.GREEN + "Your " + name + " from your last fight was returned to your inventory.");
                continue;
            }
            left = addInto(player.getEnderChest(), left);
            if (left == null) {
                player.sendMessage(org.bukkit.ChatColor.GREEN + "Your " + name + " from your last fight went in your inventory and ender chest (inventory full).");
                continue;
            }
            player.getWorld().dropItemNaturally(player.getLocation(), left);
            player.sendMessage(org.bukkit.ChatColor.RED + "Some of your " + name + " from your last fight was dropped at your feet (inventory and ender chest full).");
        }
    }

    /**
     * On join: a player with a saved pre-fight inventory who isn't in a fight never got it back (server
     * crash or shutdown mid-fight, or a failed restore), so restore it now.
     */
    public static void restoreLeftOver(final Player player) {
        if (SpawnArena.arenas != null && SpawnArena.arenas.hasFighter(player))
            return;
        final ConfigHandler handler = ConfigHandler.getInstance();
        final PlayerPreFightData data;
        synchronized (handler) {
            data = handler.getPreFightConfig().map.get(player.getUniqueId());
        }
        if (data == null)
            return;
        data.player = player;
        try {
            data.restore();
            player.sendMessage(org.bukkit.ChatColor.GREEN + "Your inventory from an arena fight that didn't finish properly has been restored.");
            SpawnArena.INSTANCE.getLogger().info("Restored " + player.getName() + "'s inventory from an unfinished fight (on join).");
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(java.util.logging.Level.SEVERE, "Couldn't restore " + player.getName()
                    + "'s inventory from an unfinished fight; use /restore " + player.getName(), e);
        }
    }

    /** "trident", "2x trident", or the item's custom name. */
    static String describe(final ItemStack item) {
        final String base = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                : item.getType().name().toLowerCase().replace('_', ' ');
        return item.getAmount() > 1 ? item.getAmount() + "x " + base : base;
    }

    private void restorePotionEffects() {
        // Whatever they picked up in the fight goes, even if they had no effects before it
        for (final PotionEffect effect : this.player.getActivePotionEffects())
            this.player.removePotionEffect(effect.getType());

        if (this.effects != null)
            for (final PotionEffect effect : this.effects)
                this.player.addPotionEffect(effect);
    }

    private ItemStack[] cloneItemStackArray(ItemStack[] items) {
        ItemStack[] newArray = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            try {
                newArray[i] = items[i].clone();
            } catch (NullPointerException e) {
                continue;
            }
        }
        return newArray;
    }

    public JSONObject toJsonObject() throws Exception {
        final JSONObject obj = new JSONObject();

        obj.put("uuid", this.uuid.toString());
        obj.put("username", this.username);
        obj.put("contents", ItemSerializer.serialize(this.contents));
        obj.put("armor_contents", ItemSerializer.serialize(this.armorContents));
        obj.put("level", this.level);
        obj.put("exp", this.exp);
        obj.put("previous_location", LocationUtils.locationToJson(this.previousLocation).toString());
        obj.put("previous_gamemode", this.previousGameMode.name());
        obj.put("health", this.health);
        obj.put("food_level", this.foodLevel);
        obj.put("held_slot", this.heldSlot);
        if (!this.extraItems.isEmpty())
            obj.put("extra_items", ItemSerializer.serialize(this.extraItems.toArray(new ItemStack[0])));

        return obj;
    }
}
