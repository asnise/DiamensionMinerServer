package com.exea.entity.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** One parsed effect preset file (docs/FORMATS.md §1). Immutable after loading. */
public final class EffectDefinition {

    public static final Set<String> TOP_KEYS = Set.of("$schema", "format", "id", "name", "description", "tags", "duration", "loop", "layers");
    static final Set<String> LAYER_KEYS = Set.of("type", "start", "duration", "interval", "anchor", "chance", "when");

    /** Where a layer happens (§1.1 anchor). */
    public record Anchor(double[] offset, boolean ground, boolean follow, boolean atTarget, float yaw) {
        public static final Anchor ORIGIN = new Anchor(new double[3], false, false, false, 0);

        public static Anchor parse(JsonObject j) {
            if (j == null) return ORIGIN;
            return new Anchor(Json.vec3(j, "offset", new double[3]), Json.bool(j, "ground", false), Json.bool(j, "follow", false),
                    "target".equals(Json.str(j, "at", "origin")), (float) Json.num(j, "yaw", 0));
        }
    }

    /** A layer: shared timing keys plus the compiled type ({@code template}, null for a plugin-registered type). */
    public static final class Layer {
        public final String type;
        public final double start, duration, interval, chance;
        public final Anchor anchor;
        public final String when;
        public final JsonObject json;
        public final LayerTemplate template;

        Layer(String type, JsonObject j, LayerTemplate template) {
            this.type = type;
            this.start = Math.max(0, Json.num(j, "start", 0));
            this.duration = Math.max(0, Json.num(j, "duration", 0));
            this.interval = Math.max(0.05, Json.num(j, "interval", 0.05));
            this.chance = Json.num(j, "chance", 1);
            this.anchor = Anchor.parse(Json.obj(j, "anchor"));
            this.when = Json.str(j, "when", null);
            this.json = j;
            this.template = template;
        }

        /** Seconds this layer keeps things alive after it starts. */
        double life() {
            return duration + (template != null ? template.extraLife(this) : 0);
        }
    }

    public final String id;
    public final String name;
    public final String description;
    public final List<String> tags;
    public final double duration;
    public final boolean loop;
    public final List<Layer> layers;

    private EffectDefinition(String id, JsonObject j, List<Layer> layers) {
        this.id = id;
        this.name = Json.str(j, "name", id);
        this.description = Json.str(j, "description", "");
        List<String> t = new ArrayList<>();
        if (j.has("tags") && j.get("tags").isJsonArray()) for (JsonElement e : j.getAsJsonArray("tags")) t.add(e.getAsString());
        this.tags = List.copyOf(t);
        this.loop = Json.bool(j, "loop", false);
        this.layers = List.copyOf(layers);
        double end = 0;
        for (Layer l : layers) end = Math.max(end, l.start + l.life());
        Double d = Json.numOrNull(j, "duration");
        this.duration = d != null ? Math.max(0.05, d) : Math.max(0.05, end);
    }

    /**
     * Parses a file. Problems go to {@code warn} (prefixed by the JSON path); a broken layer is
     * skipped, the rest still loads. Returns null only when the file is not an effect at all.
     */
    public static EffectDefinition parse(String id, JsonObject j, Consumer<String> warn) {
        int format = (int) Json.num(j, "format", 1);
        if (format > 1) {
            warn.accept("format " + format + " is newer than this EXea (1); skipped");
            return null;
        }
        String fileId = Json.str(j, "id", null);
        if (fileId != null && !fileId.equals(id)) warn.accept("id \"" + fileId + "\" does not match the file path; using \"" + id + "\"");
        for (String k : j.keySet()) if (!TOP_KEYS.contains(k)) warn.accept("unknown key \"" + k + "\" ignored");
        if (!j.has("layers") || !j.get("layers").isJsonArray()) {
            warn.accept("no \"layers\" array");
            return null;
        }
        List<Layer> layers = new ArrayList<>();
        int i = 0;
        for (JsonElement e : j.getAsJsonArray("layers")) {
            String where = "layers[" + i++ + "]";
            if (!e.isJsonObject()) {
                warn.accept(where + ": not an object");
                continue;
            }
            JsonObject lj = e.getAsJsonObject();
            String type = Json.str(lj, "type", null);
            if (type == null) {
                warn.accept(where + ": no \"type\"");
                continue;
            }
            LayerTemplate t = null;
            LayerTemplate.Factory f = LayerTemplate.BUILT_IN.get(type);
            if (f != null) {
                try {
                    t = f.compile(lj, msg -> warn.accept(where + " (" + type + "): " + msg));
                } catch (RuntimeException ex) {
                    warn.accept(where + " (" + type + "): " + ex.getMessage());
                }
                if (t == null) continue;
                for (String k : lj.keySet()) {
                    if (!LAYER_KEYS.contains(k) && !t.keys().contains(k)) warn.accept(where + " (" + type + "): unknown key \"" + k + "\" ignored");
                }
            }
            // A type that is not built in is looked up among plugin-registered layer types when it plays.
            layers.add(new Layer(type, lj, t));
        }
        return new EffectDefinition(id, j, layers);
    }
}
