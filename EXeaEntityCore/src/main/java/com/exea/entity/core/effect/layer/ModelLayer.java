package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §1.7: an ItemDisplay showing any item model (slash arcs, magic circles), scaled and turned from
 * {@code from} to {@code to} over its lifetime with one client-side interpolation.
 */
public final class ModelLayer {

    private ModelLayer() { }

    /** A value that may change over the lifetime: [x, y, z] from → to. */
    record FromTo(float[] from, float[] to) {
        static FromTo parse(JsonElement e, float def) {
            if (e == null) return new FromTo(vec(null, def), vec(null, def));
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                return new FromTo(vec(o.get("from"), def), vec(o.get("to"), def));
            }
            float[] v = vec(e, def);
            return new FromTo(v, v);
        }

        private static float[] vec(JsonElement e, float def) {
            if (e == null) return new float[]{def, def, def};
            if (e.isJsonPrimitive()) {
                float v = e.getAsFloat();
                return new float[]{v, v, v};
            }
            JsonArray a = e.getAsJsonArray();
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }

        boolean changes() {
            return from[0] != to[0] || from[1] != to[1] || from[2] != to[2];
        }
    }

    record Template(NamespacedKey model, double lifetime, FromTo scale, FromTo rotation, Display.Billboard billboard, boolean glow)
            implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("item_model", "lifetime", "scale", "rotation", "billboard", "glow"); }
        public double extraLife(EffectDefinition.Layer l) { return lifetime; }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        String m = Json.str(j, "item_model", null);
        NamespacedKey key = m == null ? null : NamespacedKey.fromString(m.toLowerCase());
        if (key == null) {
            warn.accept("\"item_model\" is required (e.g. exea:fx/slash_arc)");
            return null;
        }
        Display.Billboard bb;
        try {
            bb = Display.Billboard.valueOf(Json.str(j, "billboard", "fixed").toUpperCase());
        } catch (IllegalArgumentException e) {
            warn.accept("billboard must be fixed, vertical, horizontal or center");
            bb = Display.Billboard.FIXED;
        }
        try {
            return new Template(key, Math.max(0.05, Json.num(j, "lifetime", 0.5)), FromTo.parse(j.get("scale"), 1),
                    FromTo.parse(j.get("rotation"), 0), bb, Json.bool(j, "glow", false));
        } catch (RuntimeException e) {
            warn.accept("scale / rotation must be a number, [x,y,z] or {from, to}");
            return null;
        }
    }

    private static final class Shown {
        ItemDisplay d;
        int age;
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final List<Shown> shown = new ArrayList<>();
        private final int life;

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
            this.life = (int) Math.round(t.lifetime * 20);
        }

        private Transformation transform(float[] scale, float[] rotDeg) {
            float s = (float) scale();
            Quaternionf q = new Quaternionf().rotationYXZ((float) Math.toRadians(rotDeg[1]), (float) Math.toRadians(rotDeg[0]), (float) Math.toRadians(rotDeg[2]));
            return new Transformation(new Vector3f(), q, new Vector3f(scale[0] * s, scale[1] * s, scale[2] * s), new Quaternionf());
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(t.model);
            item.setItemMeta(meta);
            Shown s = new Shown();
            s.d = EffectManager.get().spawnDisplay(effect, f.location(), ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(t.billboard);
                d.setRotation(f.yaw, 0);
                d.setTeleportDuration(1);
                d.setTransformation(transform(t.scale.from(), t.rotation.from()));
                if (t.glow) d.setBrightness(new Display.Brightness(15, 15));
            });
            if (s.d != null) shown.add(s);
        }

        @Override
        protected boolean update() {
            for (int i = shown.size() - 1; i >= 0; i--) {
                Shown s = shown.get(i);
                if (s.d == null || !s.d.isValid()) {
                    EffectManager.get().release(effect, s.d);
                    shown.remove(i);
                    continue;
                }
                if (s.age == 1 && (t.scale.changes() || t.rotation.changes())) {
                    // one interpolation from the "from" to the "to" transform over the rest of the lifetime
                    s.d.setInterpolationDelay(0);
                    s.d.setInterpolationDuration(Math.max(1, life - 1));
                    s.d.setTransformation(transform(t.scale.to(), t.rotation.to()));
                }
                if (layer.anchor.follow() && s.age > 0) {
                    Frame f = frame();
                    if (f != null) s.d.teleport(f.location());
                }
                if (++s.age > life) {
                    EffectManager.get().release(effect, s.d);
                    shown.remove(i);
                }
            }
            return !shown.isEmpty();
        }

        @Override
        public void end() {
            for (Shown s : shown) EffectManager.get().release(effect, s.d);
            shown.clear();
        }
    }
}
