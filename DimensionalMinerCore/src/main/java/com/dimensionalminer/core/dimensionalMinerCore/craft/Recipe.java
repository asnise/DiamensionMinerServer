package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recipe content definition. Payload:
 * {@code {name, inputs:[{item,qty}], outputs:[{item,qty,chance}], station, required_level,
 * duration_seconds, energy_cost?}}. {@code version} is a hash of the payload, used to reject a
 * craft quoted against a recipe that changed (content reload) before the player confirmed.
 */
public record Recipe(String id, String name, List<Stack> inputs, List<Output> outputs, String station,
                     int requiredLevel, int durationSeconds, Integer energyCost, int version) {

    public record Stack(String item, int qty) {}

    /** chance is a percentage 0–100. */
    public record Output(String item, int qty, double chance) {}

    public static Recipe fromJson(String id, JsonObject p) {
        List<Stack> inputs = new ArrayList<>();
        if (p.has("inputs") && p.get("inputs").isJsonArray()) {
            for (JsonElement el : p.getAsJsonArray("inputs")) {
                JsonObject o = el.getAsJsonObject();
                int qty = o.has("qty") ? o.get("qty").getAsInt() : 1;
                if (qty > 0) inputs.add(new Stack(o.get("item").getAsString().toLowerCase(Locale.ROOT), qty));
            }
        }
        List<Output> outputs = new ArrayList<>();
        if (p.has("outputs") && p.get("outputs").isJsonArray()) {
            for (JsonElement el : p.getAsJsonArray("outputs")) {
                JsonObject o = el.getAsJsonObject();
                int qty = o.has("qty") ? o.get("qty").getAsInt() : 1;
                double chance = o.has("chance") ? o.get("chance").getAsDouble() : 100;
                if (qty > 0) outputs.add(new Output(o.get("item").getAsString().toLowerCase(Locale.ROOT), qty, Math.clamp(chance, 0, 100)));
            }
        }
        if (inputs.isEmpty() || outputs.isEmpty()) throw new IllegalArgumentException("recipe needs inputs and outputs");
        return new Recipe(
                id.toLowerCase(Locale.ROOT),
                p.has("name") ? p.get("name").getAsString() : id,
                List.copyOf(inputs),
                List.copyOf(outputs),
                p.has("station") ? p.get("station").getAsString().toUpperCase(Locale.ROOT) : "CRAFTING_TABLE",
                p.has("required_level") ? p.get("required_level").getAsInt() : 0,
                p.has("duration_seconds") ? p.get("duration_seconds").getAsInt() : 0,
                p.has("energy_cost") ? Integer.valueOf(Math.max(0, p.get("energy_cost").getAsInt())) : null,
                p.toString().hashCode());
    }

    public long energy(ForgeRules rules) {
        return energyCost != null ? energyCost : rules.defaultCraftEnergy();
    }

    /** The first output is the "main" result shown in the UI. */
    public Output mainOutput() {
        return outputs.getFirst();
    }
}
