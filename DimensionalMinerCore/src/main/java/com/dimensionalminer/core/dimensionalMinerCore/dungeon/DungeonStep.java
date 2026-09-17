package com.dimensionalminer.core.dimensionalMinerCore.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class DungeonStep {

    // Variables/Fields
    private final String stepId;
    private final String name;
    private final List<JsonObject> onStartActions;
    private final JsonObject onCurrentConfig;
    private final JsonObject goalConfig;
    private final List<JsonObject> onEndActions;

    // Constructors
    public DungeonStep(String stepId, String name, List<JsonObject> onStartActions,
                       JsonObject onCurrentConfig, JsonObject goalConfig, List<JsonObject> onEndActions) {
        this.stepId = stepId;
        this.name = name;
        this.onStartActions = onStartActions;
        this.onCurrentConfig = onCurrentConfig;
        this.goalConfig = goalConfig;
        this.onEndActions = onEndActions;
    }

    // Public Methods
    public static DungeonStep fromJson(JsonObject obj) {
        String stepId = obj.has("step_id") ? obj.get("step_id").getAsString() : "step";
        String name = obj.has("name") ? obj.get("name").getAsString() : stepId;

        List<JsonObject> onStart = new ArrayList<>();
        if (obj.has("on_start") && obj.get("on_start").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("on_start")) {
                if (el.isJsonObject()) onStart.add(el.getAsJsonObject());
            }
        }

        JsonObject onCurrent = obj.has("on_current") && obj.get("on_current").isJsonObject()
                ? obj.getAsJsonObject("on_current") : null;

        JsonObject goal = obj.has("goal") && obj.get("goal").isJsonObject()
                ? obj.getAsJsonObject("goal") : new JsonObject();

        List<JsonObject> onEnd = new ArrayList<>();
        if (obj.has("on_end") && obj.get("on_end").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("on_end")) {
                if (el.isJsonObject()) onEnd.add(el.getAsJsonObject());
            }
        }

        return new DungeonStep(stepId, name, onStart, onCurrent, goal, onEnd);
    }

    public String getStepId() { return stepId; }
    public String getName() { return name; }
    public List<JsonObject> getOnStartActions() { return onStartActions; }
    public JsonObject getOnCurrentConfig() { return onCurrentConfig; }
    public JsonObject getGoalConfig() { return goalConfig; }
    public List<JsonObject> getOnEndActions() { return onEndActions; }
}
