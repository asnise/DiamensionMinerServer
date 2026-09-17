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

    // Constructors
    public DungeonDefinition(String definitionId, String displayName, String description,
                             String templateMap, int requiredLevel, int timeLimitSeconds,
                             int maxPlayers, List<DungeonStep> steps, JsonObject finalRewards) {
        this.definitionId = definitionId;
        this.displayName = displayName;
        this.description = description;
        this.templateMap = templateMap;
        this.requiredLevel = requiredLevel;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxPlayers = maxPlayers;
        this.steps = steps;
        this.finalRewards = finalRewards;
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

        return new DungeonDefinition(defId, displayName, description, templateMap, requiredLevel, timeLimitSeconds, maxPlayers, stepList, finalRewards);
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
}
