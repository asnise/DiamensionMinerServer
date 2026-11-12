package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Content kind "GearSet" (Task 3.5):
 * <pre>
 * { "name": "Prim Plate", "pieces": ["armor_test_prim_helmet", ...],
 *   "bonuses": [ { "count": 2, "stats": { "defense": 4 } },
 *                { "count": 4, "stats": { "crit_chance": 2 }, "unique_effects": [ { ... } ] } ] }
 * </pre>
 * Only pieces that are actually worn (armor, main hand, off hand) and not broken count; each
 * distinct piece counts once. Every bonus whose count is reached is active.
 */
public record GearSet(String id, String name, List<String> pieces, List<Bonus> bonuses) {

    public record Bonus(int count, Map<String, Double> stats, List<UniqueEffect> effects) {}

    public static GearSet fromJson(String id, JsonObject p, java.util.function.Consumer<String> warn) {
        List<String> pieces = new ArrayList<>();
        if (p.has("pieces") && p.get("pieces").isJsonArray())
            for (JsonElement e : p.getAsJsonArray("pieces")) pieces.add(e.getAsString().toLowerCase(Locale.ROOT));
        List<Bonus> bonuses = new ArrayList<>();
        if (p.has("bonuses") && p.get("bonuses").isJsonArray()) {
            for (JsonElement e : p.getAsJsonArray("bonuses")) {
                if (!e.isJsonObject()) continue;
                JsonObject b = e.getAsJsonObject();
                Map<String, Double> stats = new HashMap<>();
                if (b.has("stats") && b.get("stats").isJsonObject())
                    for (Map.Entry<String, JsonElement> s : b.getAsJsonObject("stats").entrySet()) stats.put(s.getKey(), s.getValue().getAsDouble());
                int count = Math.max(1, b.has("count") ? b.get("count").getAsInt() : 2);
                bonuses.add(new Bonus(count, Map.copyOf(stats), UniqueEffect.listFrom(id + "_" + count, b.get("unique_effects"), warn)));
            }
        }
        bonuses.sort(Comparator.comparingInt(Bonus::count));
        return new GearSet(id, p.has("name") ? p.get("name").getAsString() : id, List.copyOf(pieces), List.copyOf(bonuses));
    }
}
