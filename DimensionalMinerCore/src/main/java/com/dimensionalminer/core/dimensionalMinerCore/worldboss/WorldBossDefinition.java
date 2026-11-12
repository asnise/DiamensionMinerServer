package com.dimensionalminer.core.dimensionalMinerCore.worldboss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Content kind {@code WorldBoss} (7.x). One boss that spawns in every channel of a Tier on a fixed
 * interval. Payload:
 * <pre>
 * { "display_name": "Stone Colossus", "tier": 0, "vanilla_type": "IRON_GOLEM",
 *   "stats": { "hp": 600, "attack": 14, "defense": 25 },
 *   "spawn": { "x": 0, "y": 64, "z": 0 }, "leash_radius": 32,
 *   "interval_minutes": 60, "lifetime_minutes": 15,
 *   "eligibility": { "min_damage_percent": 1, "min_active_seconds": 30 },
 *   "server_xp_pool": 300,
 *   "rewards": { "standard": [ {"item": "prim_metal", "qty": 3} | {"currency": "COIN", "amount": 50} ],
 *                "rare":     [ ... ] } }
 * </pre>
 * Standard rewards go to every eligible player; rare rewards go once to each player in the set
 * Top 3 damage ∪ Last Shot (someone in both still gets one rare set).
 */
public record WorldBossDefinition(String id, int version, String displayName, int tier, String vanillaType,
                                  double hp, Double attack, Double defense,
                                  double x, double y, double z, double leashRadius,
                                  int intervalMinutes, int lifetimeMinutes,
                                  double minDamagePercent, int minActiveSeconds, long serverXpPool,
                                  List<Reward> standard, List<Reward> rare) {

    /** An item ({@code item} + qty) or a currency ({@code currency} + amount). */
    public record Reward(String item, String currency, long amount) {
        public boolean isItem() { return item != null; }
        public String describe() { return isItem() ? amount + "x " + item : amount + " " + currency; }
    }

    public static final java.util.Set<String> CURRENCIES = java.util.Set.of("COIN", "ENERGY");

    public static WorldBossDefinition fromJson(String id, int version, JsonObject p) {
        JsonObject stats = obj(p, "stats");
        JsonObject spawn = obj(p, "spawn");
        JsonObject elig = obj(p, "eligibility");
        JsonObject rewards = obj(p, "rewards");
        return new WorldBossDefinition(
                id.toLowerCase(Locale.ROOT), Math.max(1, version),
                str(p, "display_name", id),
                (int) num(p, "tier", 0),
                str(p, "vanilla_type", "IRON_GOLEM").toUpperCase(Locale.ROOT),
                Math.max(1, num(stats, "hp", 500)),
                stats.has("attack") ? num(stats, "attack", 0) : null,
                stats.has("defense") ? num(stats, "defense", 0) : null,
                num(spawn, "x", 0), num(spawn, "y", 64), num(spawn, "z", 0),
                Math.max(4, num(p, "leash_radius", 32)),
                (int) Math.max(1, num(p, "interval_minutes", 60)),
                (int) Math.max(1, num(p, "lifetime_minutes", 15)),
                Math.max(0, num(elig, "min_damage_percent", 1)),
                (int) Math.max(0, num(elig, "min_active_seconds", 30)),
                (long) Math.max(0, num(p, "server_xp_pool", 0)),
                rewards(rewards.get("standard")), rewards(rewards.get("rare")));
    }

    private static List<Reward> rewards(JsonElement el) {
        List<Reward> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : (JsonArray) el) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("item") && !o.get("item").getAsString().isBlank()) {
                long qty = o.has("qty") ? o.get("qty").getAsLong() : o.has("amount") ? o.get("amount").getAsLong() : 1;
                if (qty > 0) out.add(new Reward(o.get("item").getAsString().toLowerCase(Locale.ROOT), null, qty));
            } else if (o.has("currency")) {
                String c = o.get("currency").getAsString().toUpperCase(Locale.ROOT);
                long amount = o.has("amount") ? o.get("amount").getAsLong() : 0;
                if (CURRENCIES.contains(c) && amount > 0) out.add(new Reward(null, c, amount));
            }
        }
        return out;
    }

    private static JsonObject obj(JsonObject p, String k) {
        return p.has(k) && p.get(k).isJsonObject() ? p.getAsJsonObject(k) : new JsonObject();
    }

    private static String str(JsonObject p, String k, String def) {
        return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsString() : def;
    }

    private static double num(JsonObject p, String k, double def) {
        try {
            return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsDouble() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
