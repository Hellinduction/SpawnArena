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

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerPreFightData {
    public Player player;

    protected String username;
    protected ItemStack[] contents;
    protected ItemStack[] armorContents;
    protected int level;
    protected float exp;
    protected Location previousLocation;
    protected GameMode previousGameMode;
    protected double health;
    protected int foodLevel;
    protected List<PotionEffect> effects;
    /** Items that belong to the player but didn't fit in their inventory when the fight started (e.g. a recalled trident). */
    protected List<ItemStack> extraItems = new ArrayList<>();

    public PlayerPreFightData(Player player) {
        this.player = player;
        this.username = this.player.getName();
        contents = cloneItemStackArray(player.getInventory().getContents());
        armorContents = cloneItemStackArray(player.getInventory().getArmorContents());
        level = player.getLevel();
        exp = player.getExp();
        previousLocation = player.getLocation();
        previousGameMode = player.getGameMode();
        health = player.getHealth();
        foodLevel = player.getFoodLevel();
        effects = new ArrayList<>(player.getActivePotionEffects());

        final ConfigHandler handler = ConfigHandler.getInstance();

        final File configFile = new File(SpawnArena.INSTANCE.getDataFolder(), "prefights.yml");

        if (!configFile.exists())
            handler.updatePreFightConfig();

        final PreFightConfig config = handler.getPreFightConfig();

        config.map.put(player.getUniqueId(), this);
        handler.savePreFightConfig(config);
    }

    /**
     * Deserialize PlayerPreFightData from a JSONObject
     *
     * @param obj
     * @throws IOException
     */
    public PlayerPreFightData(final JSONObject obj) throws Exception {
        this.player = Bukkit.getPlayer(UUID.fromString(obj.getString("uuid")));
        this.username = obj.getString("username");
        this.contents = ItemSerializer.deserialize(obj.getString("contents"));
        this.armorContents = ItemSerializer.deserialize(obj.getString("armor_contents"));
        this.level = obj.getInt("level");
        this.exp = obj.getFloat("exp");
        this.previousLocation = LocationUtils.locationFromJson(new JSONObject(obj.getString("previous_location")));
        this.previousGameMode = GameMode.valueOf(obj.getString("previous_gamemode"));
        this.health = obj.getDouble("health");
        this.foodLevel = obj.getInt("food_level");
        if (obj.has("extra_items")) {
            for (final ItemStack item : ItemSerializer.deserialize(obj.getString("extra_items")))
                if (item != null)
                    this.extraItems.add(item);
        }
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
        final PreFightConfig config = handler.getPreFightConfig();

        config.map.remove(player.getUniqueId());
        handler.savePreFightConfig(config);
    }

    private void returnExtraItem(final ItemStack item, final Location dropAt) {
        final String name = describe(item);

        if (this.player.getInventory().addItem(item.clone()).isEmpty()) {
            this.player.sendMessage(org.bukkit.ChatColor.RED + "Your " + name + " was returned to your inventory.");
            return;
        }
        if (this.player.getEnderChest().addItem(item.clone()).isEmpty()) {
            this.player.sendMessage(org.bukkit.ChatColor.RED + "Your inventory was full, so your " + name + " was put in your ender chest.");
            return;
        }
        this.player.getWorld().dropItemNaturally(dropAt != null ? dropAt : this.player.getLocation(), item);
        this.player.sendMessage(org.bukkit.ChatColor.RED + "Your inventory and ender chest were full, so your " + name + " was dropped at your feet.");
    }

    /** "trident", "2x trident", or the item's custom name. */
    static String describe(final ItemStack item) {
        final String base = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                : item.getType().name().toLowerCase().replace('_', ' ');
        return item.getAmount() > 1 ? item.getAmount() + "x " + base : base;
    }

    private void restorePotionEffects() {
        if (this.effects == null || this.effects.isEmpty())
            return;

        for (final PotionEffect effect : this.player.getActivePotionEffects())
            this.player.removePotionEffect(effect.getType());

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

        obj.put("uuid", this.player.getUniqueId().toString());
        obj.put("username", this.username);
        obj.put("contents", ItemSerializer.serialize(this.contents));
        obj.put("armor_contents", ItemSerializer.serialize(this.armorContents));
        obj.put("level", this.level);
        obj.put("exp", this.exp);
        obj.put("previous_location", LocationUtils.locationToJson(this.previousLocation).toString());
        obj.put("previous_gamemode", this.previousGameMode.name());
        obj.put("health", this.health);
        obj.put("food_level", this.foodLevel);
        if (!this.extraItems.isEmpty())
            obj.put("extra_items", ItemSerializer.serialize(this.extraItems.toArray(new ItemStack[0])));

        return obj;
    }
}
