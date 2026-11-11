package com.dimensionalminer.core.dimensionalMinerCore.mining;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Content kind "LootTable" (same shape the admin editor writes):
 * <pre>{ "min_rolls": 1, "max_rolls": 3,
 *   "pools": [ { "item": "prim_metal", "weight": 50, "min_qty": 1, "max_qty": 2 }, ... ] }</pre>
 * Each roll picks one pool entry by weight and a quantity in [min_qty, max_qty].
 */
public record LootTable(String id, int minRolls, int maxRolls, List<Entry> entries) {

    public record Entry(String item, int weight, int minQty, int maxQty) {}

    public static LootTable fromJson(String id, JsonObject p) {
        int minRolls = p.has("min_rolls") ? p.get("min_rolls").getAsInt() : 1;
        int maxRolls = p.has("max_rolls") ? p.get("max_rolls").getAsInt() : minRolls;
        List<Entry> entries = new ArrayList<>();
        if (p.has("pools") && p.get("pools").isJsonArray()) {
            for (JsonElement el : p.getAsJsonArray("pools")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("item")) continue;
                int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
                int min = o.has("min_qty") ? o.get("min_qty").getAsInt() : 1;
                int max = o.has("max_qty") ? o.get("max_qty").getAsInt() : min;
                if (weight > 0 && min > 0 && max >= min) {
                    entries.add(new Entry(o.get("item").getAsString(), weight, min, max));
                }
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("loot table has no valid pools");
        if (minRolls < 0 || maxRolls < minRolls) throw new IllegalArgumentException("bad min_rolls/max_rolls");
        return new LootTable(id, minRolls, maxRolls, List.copyOf(entries));
    }

    /** Rolls the table; returns item id -> total quantity (merged across rolls). */
    public Map<String, Integer> roll() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int totalWeight = entries.stream().mapToInt(Entry::weight).sum();
        int rolls = minRolls + (maxRolls > minRolls ? rnd.nextInt(maxRolls - minRolls + 1) : 0);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < rolls; i++) {
            int pick = rnd.nextInt(totalWeight);
            for (Entry e : entries) {
                pick -= e.weight();
                if (pick < 0) {
                    int qty = e.minQty() + (e.maxQty() > e.minQty() ? rnd.nextInt(e.maxQty() - e.minQty() + 1) : 0);
                    out.merge(e.item(), qty, Integer::sum);
                    break;
                }
            }
        }
        return out;
    }
}
