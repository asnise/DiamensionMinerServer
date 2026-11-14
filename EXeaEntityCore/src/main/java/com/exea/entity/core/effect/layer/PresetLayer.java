package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.exea.entity.core.effect.Origin;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §1.9: plays another effect as a layer. With {@code follow} the child plays on the same origin as
 * this effect (offset ignored); otherwise it plays fixed at this layer's anchor.
 */
public final class PresetLayer {

    private PresetLayer() { }

    public record Template(String id, double scale) implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("id", "scale"); }

        public double extraLife(EffectDefinition.Layer l) {
            EffectDefinition d = EffectManager.get().get(id);
            return d == null ? 0 : d.duration;
        }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        String id = Json.str(j, "id", null);
        if (id == null) {
            warn.accept("\"id\" is required");
            return null;
        }
        return new Template(id.toLowerCase(), Math.max(0.01, Json.num(j, "scale", 1)));
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final List<EffectInstance> children = new ArrayList<>();

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Origin o;
            if (layer.anchor.follow()) {
                o = effect.origin;
            } else {
                Frame f = frame();
                if (f == null) return;
                o = Origin.fixed(f.location(), f.yaw);
            }
            EffectInstance child = EffectManager.get().playChild(effect, t.id, o, t.scale);
            if (child != null) children.add(child);
        }

        @Override
        protected boolean update() {
            children.removeIf(c -> !EffectManager.get().tickChild(c));
            return !children.isEmpty();
        }

        @Override
        public void end() {
            for (EffectInstance c : children) c.stop();
            children.clear();
        }
    }
}
