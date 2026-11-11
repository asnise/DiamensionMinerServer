package com.dimensionalminer.core.dimensionalMinerCore.mining;

import com.dimensionalminer.core.dimensionalMinerCore.ContentRegistryManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns item ids + amounts into ItemStacks (main thread) and back into mailbox payloads. */
public final class RewardItems {

    private RewardItems() {}

    /**
     * Builds stacks for (item id -> amount), split by max stack size. Content items come from the
     * registry; anything else must be a vanilla item material.
     * @throws IllegalArgumentException for an unknown item id
     */
    public static List<ItemStack> build(ContentRegistryManager registry, Map<String, Integer> amounts) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) {
            ItemStack proto = prototype(registry, e.getKey());
            if (proto == null) throw new IllegalArgumentException("unknown item id: " + e.getKey());
            int perStack = Math.max(1, proto.getAmount());
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(perStack, left);
                ItemStack s = proto.clone();
                s.setAmount(n);
                out.add(s);
                left -= n;
            }
        }
        return out;
    }

    /** A full stack of the item, or null if unknown. */
    private static ItemStack prototype(ContentRegistryManager registry, String itemId) {
        if (registry != null && registry.hasItem(itemId)) {
            return registry.createItemStack(itemId, Integer.MAX_VALUE); // clamped to the definition's stack size
        }
        Material mat = Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isItem() || mat.isAir()) return null;
        return new ItemStack(mat, mat.getMaxStackSize());
    }

    /** Mailbox payload ({"items":[{"item_id","amount"}]}) for stacks that did not fit. */
    public static String mailboxPayload(ContentRegistryManager registry, Collection<ItemStack> stacks, String type) {
        Map<String, Integer> amounts = new HashMap<>();
        for (ItemStack s : stacks) amounts.merge(itemIdOf(registry, s), s.getAmount(), Integer::sum);
        JsonArray items = new JsonArray();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) {
            JsonObject it = new JsonObject();
            it.addProperty("item_id", e.getKey());
            it.addProperty("amount", e.getValue());
            items.add(it);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.add("items", items);
        return payload.toString();
    }

    private static String itemIdOf(ContentRegistryManager registry, ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && registry != null) {
            String defId = meta.getPersistentDataContainer().get(registry.getDefIdKey(), PersistentDataType.STRING);
            if (defId != null && !defId.isBlank()) return defId;
        }
        return stack.getType().name().toLowerCase(Locale.ROOT);
    }

    /** "2x Prim Metal, 1x Coal" for chat. */
    public static String describe(ContentRegistryManager registry, Map<String, Integer> amounts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) {
            String name = registry != null && registry.hasItem(e.getKey())
                    ? registry.getItem(e.getKey()).getDisplay()
                    : e.getKey().toLowerCase(Locale.ROOT).replace('_', ' ');
            parts.add(e.getValue() + "x " + name);
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }
}
