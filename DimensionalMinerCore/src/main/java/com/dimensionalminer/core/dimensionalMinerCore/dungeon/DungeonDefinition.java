package com.dimensionalminer.core.dimensionalMinerCore.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class DungeonDefinition {

    // Variables/Fields
    private final String definitionId;
    private final String displayName;
    private final String description;
    private final String templateMap;
    private final int requiredLevel;
    private final int timeLimitSeconds;
    private final int maxPlayers;
    private final List<DungeonStep> steps;
    private final JsonObject finalRewards;
    /** What the party leader pays to open a run (6.2): key items and/or currency. Empty = free. */
    private final List<EntryCost> entryCost;

    /** One entry cost: an item ({@code item} + amount) or a currency ({@code currency} + amount). */
    public record EntryCost(String item, String currency, long amount) {
        public boolean isItem() { return item != null; }
        public String describe() { return amount + (isItem() ? "x " + item : " " + currency); }
    }

    public static final java.util.Set<String> COST_CURRENCIES = java.util.Set.of("COIN", "ENERGY");

    // Constructors
    public DungeonDefinition(String definitionId, String displayName, String description,
                             String templateMap, int requiredLevel, int timeLimitSeconds,
                             int maxPlayers, List<DungeonStep> steps, JsonObject finalRewards, List<EntryCost> entryCost) {
        this.definitionId = definitionId;
        this.displayName = displayName;
        this.description = description;
        this.templateMap = templateMap;
        this.requiredLevel = requiredLevel;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxPlayers = maxPlayers;
        this.steps = steps;
        this.finalRewards = finalRewards;
        this.entryCost = List.copyOf(entryCost);
    }

    // Public Methods
    public static DungeonDefinition fromJson(String defId, JsonObject payload) {
        String displayName = payload.has("display_name") ? payload.get("display_name").getAsString() : defId;
        String description = payload.has("description") ? payload.get("description").getAsString() : "";
        String templateMap = payload.has("template_map") ? payload.get("template_map").getAsString() : "ind_t0";
        int requiredLevel = payload.has("required_level") ? payload.get("required_level").getAsInt() : 1;
        int timeLimitSeconds = payload.has("time_limit_seconds") ? payload.get("time_limit_seconds").getAsInt() : 900;
        int maxPlayers = payload.has("max_players") ? payload.get("max_players").getAsInt() : 4;

        List<DungeonStep> stepList = new ArrayList<>();
        if (payload.has("steps") && payload.get("steps").isJsonArray()) {
            JsonArray stepsArr = payload.getAsJsonArray("steps");
            for (JsonElement el : stepsArr) {
                if (el.isJsonObject()) {
                    stepList.add(DungeonStep.fromJson(el.getAsJsonObject()));
                }
            }
        }

        JsonObject finalRewards = payload.has("final_rewards") && payload.get("final_rewards").isJsonObject()
                ? payload.getAsJsonObject("final_rewards") : new JsonObject();

        return new DungeonDefinition(defId, displayName, description, templateMap, requiredLevel, timeLimitSeconds, maxPlayers,
                stepList, finalRewards, parseEntryCost(defId, payload.get("entry_cost")));
    }

    /**
     * "entry_cost": one object or an array of them, each {"item": id, "qty": n} or
     * {"currency": "COIN"|"ENERGY", "amount": n}. Invalid entries are skipped with a warning.
     */
    private static List<EntryCost> parseEntryCost(String defId, JsonElement el) {
        List<EntryCost> out = new ArrayList<>();
        if (el == null || el.isJsonNull()) return out;
        List<JsonElement> list = new ArrayList<>();
        if (el.isJsonArray()) el.getAsJsonArray().forEach(list::add); else list.add(el);
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("item") && !o.get("item").getAsString().isBlank()) {
                int qty = o.has("qty") ? o.get("qty").getAsInt() : o.has("amount") ? o.get("amount").getAsInt() : 1;
                if (qty > 0) out.add(new EntryCost(o.get("item").getAsString().toLowerCase(java.util.Locale.ROOT), null, qty));
            } else if (o.has("currency")) {
                String cur = o.get("currency").getAsString().toUpperCase(java.util.Locale.ROOT);
                long amount = o.has("amount") ? o.get("amount").getAsLong() : 0;
                if (COST_CURRENCIES.contains(cur) && amount > 0) out.add(new EntryCost(null, cur, amount));
                else java.util.logging.Logger.getLogger("DimensionalMinerCore").warning("[Dungeon] " + defId + ": invalid entry_cost " + o);
            }
        }
        return out;
    }

    public String getDefinitionId() { return definitionId; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getTemplateMap() { return templateMap; }
    public int getRequiredLevel() { return requiredLevel; }
    public int getTimeLimitSeconds() { return timeLimitSeconds; }
    public int getMaxPlayers() { return maxPlayers; }
    public List<DungeonStep> getSteps() { return steps; }
    public JsonObject getFinalRewards() { return finalRewards; }
    public List<EntryCost> getEntryCost() { return entryCost; }

    /** content_definitions.version of this definition (instance_runs.config_version). */
    private int version = 1;
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = Math.max(1, version); }
}
