package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.FileReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ContentRegistryManager {

    private final DimensionalMinerCore plugin;
    private final NamespacedKey defIdKey;
    // Replaced as a whole on reload (never cleared in place), so readers on the main thread
    // always see either the complete old registry or the complete new one.
    private volatile Map<String, ItemDefinition> registry = Map.of();
    private volatile Map<String, com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition> dungeonRegistry = Map.of();
    private volatile Map<String, com.dimensionalminer.core.dimensionalMinerCore.mining.OreType> oreRegistry = Map.of();
    private volatile Map<String, com.dimensionalminer.core.dimensionalMinerCore.mining.LootTable> lootRegistry = Map.of();
    private final DecimalFormat df = new DecimalFormat("#.##");

    /** Everything parsed from one registry file; swapped in as a unit. */
    private record Parsed(Map<String, ItemDefinition> items,
                          Map<String, com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition> dungeons,
                          Map<String, com.dimensionalminer.core.dimensionalMinerCore.mining.OreType> ores,
                          Map<String, com.dimensionalminer.core.dimensionalMinerCore.mining.LootTable> loot) {
        Parsed() { this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()); }
    }

    public ContentRegistryManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.defIdKey = new NamespacedKey(plugin, "definition_id");
    }

    public NamespacedKey getDefIdKey() {
        return defIdKey;
    }

    /**
     * (Re)loads content_registry.json into fresh maps and swaps them in only if the file parsed.
     * On failure the previous registry stays active, so a bad export never empties the game.
     * The plugin reads content only from this file (exported by the web console); it has no
     * access to the dimensional_content database.
     *
     * @return true if a registry file was found and loaded
     */
    public boolean loadAll() {
        Parsed parsed = new Parsed();

        File file = findRegistryFile();
        if (file == null) {
            plugin.getLogger().severe("[ContentRegistry] content_registry.json not found; keeping "
                    + registry.size() + " items, " + dungeonRegistry.size() + " dungeons already loaded.");
            return false;
        }
        try {
            loadFromFile(file, parsed);
        } catch (Exception e) {
            plugin.getLogger().severe("[ContentRegistry] Failed to read " + file.getPath() + " (" + e.getMessage()
                    + "); keeping the previously loaded registry.");
            return false;
        }

        // An ore whose loot table is missing would deplete nodes and give nothing: drop it loudly.
        parsed.ores().values().removeIf(ore -> {
            if (parsed.loot().containsKey(ore.lootTableId().toLowerCase(Locale.ROOT))) return false;
            plugin.getLogger().warning("[ContentRegistry] OreType/" + ore.id() + " skipped: loot table '"
                    + ore.lootTableId() + "' not found");
            return true;
        });

        registry = Map.copyOf(parsed.items());
        dungeonRegistry = Map.copyOf(parsed.dungeons());
        oreRegistry = Map.copyOf(parsed.ores());
        lootRegistry = Map.copyOf(parsed.loot());
        plugin.getLogger().info("[ContentRegistry] Loaded " + parsed.items().size() + " items, " + parsed.dungeons().size()
                + " dungeons, " + parsed.ores().size() + " ore types, " + parsed.loot().size() + " loot tables from " + file.getPath());
        return true;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.mining.OreType getOreType(String id) {
        return id == null ? null : oreRegistry.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<com.dimensionalminer.core.dimensionalMinerCore.mining.OreType> getAllOreTypes() {
        return oreRegistry.values();
    }

    public com.dimensionalminer.core.dimensionalMinerCore.mining.LootTable getLootTable(String id) {
        return id == null ? null : lootRegistry.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hasItem(String id) {
        if (id == null) return false;
        return registry.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public ItemDefinition getItem(String id) {
        if (id == null) return null;
        return registry.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hasDungeon(String id) {
        if (id == null) return false;
        return dungeonRegistry.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition getDungeon(String id) {
        if (id == null) return null;
        return dungeonRegistry.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition> getAllDungeons() {
        return dungeonRegistry.values();
    }

    public Collection<ItemDefinition> getAllItems() {
        return registry.values();
    }

    private File findRegistryFile() {
        File[] candidates = new File[] {
                new File(plugin.getDataFolder(), "content_registry.json"),
                new File(plugin.getDataFolder().getParentFile(), "DimensionalMinerCore/content_registry.json"),
                new File("../../database/content_registry.json"),
                new File("../database/content_registry.json"),
                new File("database/content_registry.json")
        };

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.isFile()) return candidate;
        }
        return null;
    }

    /** Parses the whole file into the given maps; throws if the file itself is unreadable. */
    private void loadFromFile(File file, Parsed out) throws Exception {
        try (FileReader reader = new FileReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String kind = entry.getKey();
                if (!entry.getValue().isJsonArray()) continue;
                JsonArray array = entry.getValue().getAsJsonArray();
                for (JsonElement el : array) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String defId = obj.has("definition_id") ? obj.get("definition_id").getAsString() : null;
                    if (defId == null) continue;
                    JsonObject payload = obj.has("payload") && obj.get("payload").isJsonObject()
                            ? obj.getAsJsonObject("payload")
                            : new JsonObject();
                    try {
                        String key = defId.toLowerCase(Locale.ROOT);
                        if (kind.equalsIgnoreCase("Dungeon")) {
                            com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition d =
                                    com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition.fromJson(defId, payload);
                            out.dungeons().put(d.getDefinitionId().toLowerCase(Locale.ROOT), d);
                        } else if (kind.equalsIgnoreCase("OreType")) {
                            out.ores().put(key, com.dimensionalminer.core.dimensionalMinerCore.mining.OreType.fromJson(defId, payload));
                        } else if (kind.equalsIgnoreCase("LootTable")) {
                            out.loot().put(key, com.dimensionalminer.core.dimensionalMinerCore.mining.LootTable.fromJson(defId, payload));
                        } else {
                            ItemDefinition def = parseDefinition(defId, kind, payload);
                            if (def != null) {
                                out.items().put(def.getId().toLowerCase(Locale.ROOT), def);
                            }
                        }
                    } catch (Exception e) {
                        // One broken definition must not take the rest of the registry down with it.
                        plugin.getLogger().warning("[ContentRegistry] Skipped " + kind + "/" + defId + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private ItemDefinition parseDefinition(String id, String kind, JsonObject p) {
        if (kind.equalsIgnoreCase("LootTable") || kind.equalsIgnoreCase("Recipe") || kind.equalsIgnoreCase("Generator")) {
            return null;
        }

        String name = p.has("name") ? p.get("name").getAsString() : id;
        String display = p.has("display") ? p.get("display").getAsString() : name;
        String icon = p.has("icon") ? p.get("icon").getAsString() : null;
        String category = p.has("category") ? p.get("category").getAsString() : kind;
        String rarity = p.has("rarity") ? p.get("rarity").getAsString() : "COMMON";
        String binding = p.has("binding") ? p.get("binding").getAsString() : "UNBOUND";
        int stack = p.has("stack") ? p.get("stack").getAsInt() : 64;
        Integer durability = p.has("durability") ? p.get("durability").getAsInt() : null;
        String description = p.has("description") ? p.get("description").getAsString() : null;

        Map<String, Double> stats = new HashMap<>();
        if (p.has("base_stats") && p.get("base_stats").isJsonObject()) {
            JsonObject s = p.getAsJsonObject("base_stats");
            for (Map.Entry<String, JsonElement> statEntry : s.entrySet()) {
                try {
                    stats.put(statEntry.getKey(), statEntry.getValue().getAsDouble());
                } catch (Exception e) {
                    plugin.getLogger().warning("[ContentRegistry] " + id + ": stat '" + statEntry.getKey() + "' is not a number, ignored");
                }
            }
        }

        return new ItemDefinition(id, kind, name, display, icon, category, rarity, binding, stack, durability, description, stats);
    }

    public ItemStack createItemStack(String definitionId, int amount) {
        ItemDefinition def = getItem(definitionId);
        Material mat = resolveMaterial(def, definitionId);
        int maxStack = def != null ? def.getStack() : mat.getMaxStackSize();
        int finalAmount = Math.max(1, Math.min(amount, maxStack));

        ItemStack stack = new ItemStack(mat, finalAmount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.getPersistentDataContainer().set(defIdKey, PersistentDataType.STRING, definitionId.toLowerCase(Locale.ROOT));

        if (def != null) {
            ChatColor rarityColor = getRarityColor(def.getRarity());
            meta.setDisplayName(rarityColor + "" + ChatColor.BOLD + def.getDisplay());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "- " + rarityColor + def.getRarity().toUpperCase(Locale.ROOT) + " " + ChatColor.GRAY + def.getCategory().toUpperCase(Locale.ROOT));

            if (!def.getBaseStats().isEmpty()) {
                lore.add("");
                lore.add(ChatColor.GOLD + "Attributes:");
                Map<String, Double> s = def.getBaseStats();
                if (s.containsKey("attack_damage")) {
                    lore.add(ChatColor.RED + " [Damage] Attack Damage: +" + df.format(s.get("attack_damage")));
                }
                if (s.containsKey("attack_speed")) {
                    lore.add(ChatColor.YELLOW + " [Speed] Attack Speed: +" + df.format(s.get("attack_speed")));
                }
                if (s.containsKey("defense")) {
                    lore.add(ChatColor.BLUE + " [Armor] Defense: +" + df.format(s.get("defense")));
                }
                if (s.containsKey("mining_speed")) {
                    lore.add(ChatColor.AQUA + " [Mining] Mining Speed: +" + df.format(s.get("mining_speed")));
                }
                if (s.containsKey("crit_chance")) {
                    lore.add(ChatColor.LIGHT_PURPLE + " [Crit] Crit Chance: +" + df.format(s.get("crit_chance")) + "%");
                }
                if (s.containsKey("luck")) {
                    lore.add(ChatColor.GREEN + " [Luck] Mining Luck: +" + df.format(s.get("luck")));
                }
            }

            if (def.getDescription() != null && !def.getDescription().isBlank()) {
                lore.add("");
                lore.add(ChatColor.ITALIC + "" + ChatColor.GRAY + def.getDescription());
            }

            if (def.getBinding() != null && !def.getBinding().equalsIgnoreCase("UNBOUND")) {
                lore.add(ChatColor.DARK_AQUA + "[Bound: " + def.getBinding() + "]");
            }

            lore.add(ChatColor.DARK_GRAY + "ID: " + def.getId());
            meta.setLore(lore);
        } else {
            meta.setDisplayName(ChatColor.WHITE + definitionId);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    public Material resolveMaterial(ItemDefinition def, String fallbackId) {
        if (def != null && def.getIcon() != null && !def.getIcon().isBlank()) {
            Material m = Material.matchMaterial(def.getIcon().toUpperCase(Locale.ROOT));
            if (m != null && m.isItem() && m != Material.AIR) return m;
        }

        Material direct = Material.matchMaterial(fallbackId.toUpperCase(Locale.ROOT));
        if (direct != null && direct.isItem() && direct != Material.AIR) {
            return direct;
        }

        if (def != null) {
            String cat = def.getCategory().toUpperCase(Locale.ROOT);
            if (cat.contains("WEAPON") || cat.contains("SWORD")) return Material.IRON_SWORD;
            if (cat.contains("ARMOR")) return Material.IRON_CHESTPLATE;
            if (cat.contains("TOOL") || cat.contains("PICKAXE")) return Material.IRON_PICKAXE;
            if (cat.contains("MATERIAL")) return Material.IRON_INGOT;
            if (cat.contains("CONSUMABLE") || cat.contains("FOOD")) return Material.GOLDEN_APPLE;
        }

        return Material.CHEST;
    }

    private ChatColor getRarityColor(String rarity) {
        if (rarity == null) return ChatColor.WHITE;
        switch (rarity.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON": return ChatColor.GREEN;
            case "RARE": return ChatColor.AQUA;
            case "EPIC": return ChatColor.DARK_PURPLE;
            case "LEGENDARY": return ChatColor.GOLD;
            case "MYTHIC": return ChatColor.LIGHT_PURPLE;
            default: return ChatColor.WHITE;
        }
    }
}
