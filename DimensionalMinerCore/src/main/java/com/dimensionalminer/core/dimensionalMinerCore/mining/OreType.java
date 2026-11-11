package com.dimensionalminer.core.dimensionalMinerCore.mining;

import com.google.gson.JsonObject;
import org.bukkit.Material;

import java.util.Locale;

/**
 * Content kind "OreType": what a mining node looks like and what it gives.
 * <pre>{ "display_name": "Prim Metal Ore",
 *   "block": "IRON_ORE",            // shown while READY
 *   "depleted_block": "STONE",      // shown while waiting to respawn
 *   "loot_table": "ore_prim_metal", // LootTable definition id
 *   "respawn_seconds": 60,
 *   "required_tool_tier": "STONE" } // NONE | WOOD | STONE | IRON | DIAMOND | NETHERITE (pickaxe)</pre>
 */
public record OreType(String id, String displayName, Material block, Material depletedBlock,
                      String lootTableId, int respawnSeconds, int requiredToolTier) {

    public static OreType fromJson(String id, JsonObject p) {
        String name = p.has("display_name") ? p.get("display_name").getAsString() : id;
        Material block = material(p, "block", null);
        Material depleted = material(p, "depleted_block", Material.STONE);
        if (block == null || !block.isBlock()) throw new IllegalArgumentException("'block' must be a block material");
        if (!depleted.isBlock()) throw new IllegalArgumentException("'depleted_block' must be a block material");
        if (!p.has("loot_table")) throw new IllegalArgumentException("'loot_table' is required");
        int respawn = p.has("respawn_seconds") ? p.get("respawn_seconds").getAsInt() : 60;
        if (respawn < 1) throw new IllegalArgumentException("respawn_seconds must be >= 1");
        String tier = p.has("required_tool_tier") ? p.get("required_tool_tier").getAsString() : "NONE";
        return new OreType(id, name, block, depleted, p.get("loot_table").getAsString(), respawn, ToolTier.parse(tier));
    }

    private static Material material(JsonObject p, String key, Material fallback) {
        if (!p.has(key)) return fallback;
        Material m = Material.matchMaterial(p.get(key).getAsString().toUpperCase(Locale.ROOT));
        if (m == null) throw new IllegalArgumentException("unknown material in '" + key + "': " + p.get(key).getAsString());
        return m;
    }

    /** Pickaxe tiers. Golden pickaxes count as wood (vanilla harvest level). */
    public static final class ToolTier {
        public static final int NONE = 0;

        private ToolTier() {}

        static int parse(String name) {
            return switch (name.toUpperCase(Locale.ROOT)) {
                case "NONE" -> 0;
                case "WOOD", "WOODEN", "GOLD", "GOLDEN" -> 1;
                case "STONE", "COPPER" -> 2;
                case "IRON" -> 3;
                case "DIAMOND" -> 4;
                case "NETHERITE" -> 5;
                default -> throw new IllegalArgumentException("unknown required_tool_tier: " + name);
            };
        }

        /** Tier of the pickaxe in hand, or 0 if it is not a pickaxe. */
        public static int of(Material tool) {
            if (tool == null) return 0;
            String n = tool.name();
            if (!n.endsWith("_PICKAXE")) return 0;
            try {
                return parse(n.substring(0, n.length() - "_PICKAXE".length()));
            } catch (IllegalArgumentException unknownMaterialFamily) {
                return 1; // a pickaxe of a family we don't know yet: treat as the lowest tier
            }
        }

        public static String name(int tier) {
            return switch (tier) {
                case 0 -> "none";
                case 1 -> "wooden";
                case 2 -> "stone";
                case 3 -> "iron";
                case 4 -> "diamond";
                default -> "netherite";
            };
        }
    }
}
