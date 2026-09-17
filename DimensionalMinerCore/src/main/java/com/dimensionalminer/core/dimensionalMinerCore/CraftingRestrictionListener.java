package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class CraftingRestrictionListener implements Listener {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final NamespacedKey defIdKey;

    // Constructors
    public CraftingRestrictionListener(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.defIdKey = plugin.getContentRegistryManager().getDefIdKey();
    }

    // Public Methods
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        if (hasCustomMaterial(inv)) {
            Recipe recipe = event.getRecipe();
            if (isVanillaMaterialRecipe(recipe)) {
                inv.setResult(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        CraftingInventory inv = event.getInventory();
        if (hasCustomMaterial(inv)) {
            Recipe recipe = event.getRecipe();
            if (isVanillaMaterialRecipe(recipe)) {
                event.setCancelled(true);
            }
        }
    }

    // Private Methods
    private boolean hasCustomMaterial(CraftingInventory inv) {
        ItemStack[] matrix = inv.getMatrix();
        for (ItemStack item : matrix) {
            if (item == null || item.getType() == Material.AIR) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            if (meta.getPersistentDataContainer().has(defIdKey, PersistentDataType.STRING)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVanillaMaterialRecipe(Recipe recipe) {
        if (recipe == null) return false;
        ItemStack result = recipe.getResult();
        if (result == null || result.getType() == Material.AIR) return false;

        Material mat = result.getType();
        String name = mat.name();
        
        return name.startsWith("IRON_")
                || name.equals("SHIELD")
                || name.equals("SHEARS")
                || name.equals("BUCKET")
                || name.equals("MINECART")
                || name.equals("ANVIL")
                || name.equals("CHIPPED_ANVIL")
                || name.equals("DAMAGED_ANVIL")
                || name.equals("CROSSBOW")
                || name.equals("HEAVY_WEIGHTED_PRESSURE_PLATE")
                || name.equals("IRON_BARS")
                || name.equals("IRON_DOOR")
                || name.equals("IRON_TRAPDOOR")
                || name.equals("HOPPER")
                || name.equals("CAULDRON")
                || name.equals("TRIPWIRE_HOOK")
                || name.equals("FLINT_AND_STEEL")
                || name.equals("COMPASS")
                || name.equals("PISTON")
                || name.equals("CHAIN");
    }
}
