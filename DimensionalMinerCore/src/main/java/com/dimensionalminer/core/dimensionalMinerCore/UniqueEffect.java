package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Unique Effect on gear or a set bonus (Task 3.4), from payload "unique_effects": [ ... ]:
 * <pre>
 * { "id": "flame_burst", "name": "Flame Burst", "trigger": "ON_HIT", "chance": 25, "cooldown": 4,
 *   "group": "fire", "action": "BURN", "value": 2, "duration": 3 }
 * </pre>
 * trigger: ON_HIT / ON_CRIT / ON_KILL (when the wearer attacks) or ON_HURT (when the wearer is hit).
 * action / value:
 * EXTRA_DAMAGE = value damage (through defense) · BURN = value damage per second for duration ·
 * LIFESTEAL = heal value % of the damage dealt (capped) · SLOW = Slowness level value for duration ·
 * WEAKEN = target deals value % less damage for duration · SHIELD = value absorption for duration ·
 * REFLECT = value % of the damage taken back to the attacker.
 * chance in %, cooldown in seconds (per wearer and effect id), group = only one effect of a group procs per hit.
 */
public record UniqueEffect(String id, String name, Trigger trigger, double chance, double cooldown, String group,
                           Action action, double value, double duration) {

    public enum Trigger { ON_HIT, ON_CRIT, ON_KILL, ON_HURT }

    public enum Action { EXTRA_DAMAGE, BURN, LIFESTEAL, SLOW, WEAKEN, SHIELD, REFLECT }

    public static UniqueEffect fromJson(String ownerId, int index, JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString().toLowerCase(Locale.ROOT) : ownerId + "_" + index;
        Action action = Action.valueOf(o.get("action").getAsString().toUpperCase(Locale.ROOT));
        Trigger trigger = o.has("trigger") ? Trigger.valueOf(o.get("trigger").getAsString().toUpperCase(Locale.ROOT))
                : action == Action.SHIELD || action == Action.REFLECT ? Trigger.ON_HURT : Trigger.ON_HIT;
        return new UniqueEffect(id,
                o.has("name") ? o.get("name").getAsString() : id,
                trigger,
                Math.clamp(o.has("chance") ? o.get("chance").getAsDouble() : 100, 0, 100),
                Math.max(0, o.has("cooldown") ? o.get("cooldown").getAsDouble() : 0),
                o.has("group") ? o.get("group").getAsString().toLowerCase(Locale.ROOT) : id,
                action,
                o.has("value") ? o.get("value").getAsDouble() : 0,
                Math.max(0, o.has("duration") ? o.get("duration").getAsDouble() : 0));
    }

    /** Parses an array of effects; a broken entry is skipped (reported through {@code warn}). */
    public static List<UniqueEffect> listFrom(String ownerId, JsonElement el, java.util.function.Consumer<String> warn) {
        List<UniqueEffect> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        int i = 0;
        for (JsonElement e : el.getAsJsonArray()) {
            i++;
            if (!e.isJsonObject()) continue;
            try {
                out.add(fromJson(ownerId, i, e.getAsJsonObject()));
            } catch (Exception ex) {
                warn.accept(ownerId + ": unique effect #" + i + " ignored (" + ex.getMessage() + ")");
            }
        }
        return List.copyOf(out);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** "25% on hit: burns 2/s for 3s (CD 4s)" */
    public String describe() {
        String when = switch (trigger) {
            case ON_HIT -> "on hit";
            case ON_CRIT -> "on crit";
            case ON_KILL -> "on kill";
            case ON_HURT -> "when hit";
        };
        String what = switch (action) {
            case EXTRA_DAMAGE -> "+" + num(value) + " damage";
            case BURN -> "burns " + num(value) + "/s for " + num(duration) + "s";
            case LIFESTEAL -> "heals " + num(value) + "% of the damage";
            case SLOW -> "Slowness " + num(value) + " for " + num(duration) + "s";
            case WEAKEN -> "target deals -" + num(value) + "% for " + num(duration) + "s";
            case SHIELD -> num(value) + " shield for " + num(duration) + "s";
            case REFLECT -> "reflects " + num(value) + "% of the damage";
        };
        return (chance < 100 ? num(chance) + "% " : "") + when + ": " + what + (cooldown > 0 ? " (CD " + num(cooldown) + "s)" : "");
    }
}
