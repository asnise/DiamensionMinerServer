package com.exea.entity.core.skill;

import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Origin;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code telegraph} and {@code sound} actions: one effect layer with the action's keys, compiled to a
 * one-layer effect when the moveset loads and played at the action's place.
 */
record InlineEffectAction(EffectDefinition effect, Set<String> keys, boolean ground) implements SkillAction {

    static SkillAction telegraph(JsonObject j, Consumer<String> warn) {
        return make("telegraph", j, Set.of("shape", "duration", "style", "color", "fill"), true, warn);
    }

    static SkillAction sound(JsonObject j, Consumer<String> warn) {
        return make("sound", j, Set.of("sound", "volume", "pitch", "category"), false, warn);
    }

    private static SkillAction make(String type, JsonObject j, Set<String> keys, boolean ground, Consumer<String> warn) {
        JsonObject layer = new JsonObject();
        layer.addProperty("type", type);
        for (String k : keys) if (j.has(k)) layer.add(k, j.get(k).deepCopy());
        if (ground) {
            JsonObject a = new JsonObject();
            a.addProperty("ground", true);
            layer.add("anchor", a);
        }
        JsonObject def = new JsonObject();
        JsonArray layers = new JsonArray();
        layers.add(layer);
        def.add("layers", layers);
        boolean[] bad = {false};
        EffectDefinition d = EffectDefinition.parse("skill-" + type, def, m -> {
            bad[0] = true;
            warn.accept(m.replaceFirst("^layers\\[0] \\(\\w+\\): ", ""));
        });
        if (d == null || d.layers.isEmpty()) return null;
        return new InlineEffectAction(d, keys, ground);
    }

    public void run(ActionContext ctx) {
        Frame f = ctx.frame();
        EffectManager.get().play(effect, Origin.fixed(f.location(), f.yaw), EffectOptions.create().target(ctx.target));
    }
}
