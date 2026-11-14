package com.exea.entity.core.skill;

import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.Consumer;

/**
 * §2.4 {@code fx}: plays an effect preset. On a bone with {@code follow} it tracks the bone; otherwise it
 * stays at the action's place (anchor offset and ground snap included).
 */
record FxAction(String id, double scale, boolean follow) implements SkillAction {

    public Set<String> keys() { return Set.of("id", "scale", "follow"); }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        String id = Json.str(j, "id", null);
        if (id == null) {
            warn.accept("\"id\" (an effect preset) is required");
            return null;
        }
        return new FxAction(id.toLowerCase(), Math.max(0.01, Json.num(j, "scale", 1)), Json.bool(j, "follow", false));
    }

    public void run(ActionContext ctx) {
        EffectOptions o = EffectOptions.create().scale(scale).target(ctx.target).follow(follow);
        EffectManager fx = EffectManager.get();
        if (follow && ctx.impact == null && ctx.spec.bone != null) {
            fx.play(id, ctx.active(), ctx.spec.bone, o);
        } else if (follow && ctx.impact == null) {
            fx.play(id, ctx.driver(), o);
        } else {
            Frame f = ctx.frame();
            fx.play(id, f.location(), o.yaw(f.yaw));
        }
    }
}
