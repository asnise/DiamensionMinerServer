package com.exea.entity.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Small readers for effect / moveset JSON (docs/FORMATS.md §0). Bad values fall back to the default. */
public final class Json {

    private Json() { }

    /** A number, or [min, max] rolled uniformly each time. */
    public record Range(double min, double max) {
        public static Range of(double v) { return new Range(v, v); }
        public double roll(Random r) { return min == max ? min : min + r.nextDouble() * (max - min); }
        public Range times(double k) { return new Range(min * k, max * k); }
    }

    public static double num(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        return e.getAsDouble();
    }

    public static Double numOrNull(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static Range range(JsonObject o, String key, double defMin, double defMax) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null) return new Range(defMin, defMax);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return Range.of(e.getAsDouble());
        if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            JsonArray a = e.getAsJsonArray();
            double x = a.get(0).getAsDouble(), y = a.get(1).getAsDouble();
            return new Range(Math.min(x, y), Math.max(x, y));
        }
        return new Range(defMin, defMax);
    }

    public static double[] vec3(JsonObject o, String key, double[] def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) return def;
        JsonArray a = e.getAsJsonArray();
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    /** One string, or a list of strings (a block list). */
    public static List<String> strings(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        else if (e != null && e.isJsonPrimitive()) out.add(e.getAsString());
        else if (def != null) out.add(def);
        return out;
    }

    /** "#rrggbb" or "#aarrggbb"; null when absent or malformed. */
    public static Color color(JsonObject o, String key) {
        String s = str(o, key, null);
        if (s == null || !s.startsWith("#")) return null;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            if (s.length() == 9) return Color.fromARGB((int) v);
            if (s.length() == 7) return Color.fromRGB((int) v);
        } catch (NumberFormatException ignored) {
            // falls through
        }
        return null;
    }

    public static String key(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        return k.contains(":") ? k : "minecraft:" + k;
    }
}
