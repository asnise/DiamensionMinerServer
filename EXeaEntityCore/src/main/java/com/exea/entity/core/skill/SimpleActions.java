package com.exea.entity.core.skill;

import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.event.EXeaSkillEvent;
import com.exea.entity.core.effect.Json;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.util.Set;
import java.util.function.Consumer;

/** The small action types: {@code animation} and {@code event}. */
final class SimpleActions {

    private SimpleActions() { }

    /** Plays another animation now; the skill clock keeps running. */
    record Animation(String name, float speed) implements SkillAction {
        public Set<String> keys() { return Set.of("name", "speed"); }

        public void run(ActionContext ctx) {
            ctx.active().playAnimation(name, speed * (float) ctx.caster.phaseSpeed(), EXeaEntity.Loop.ONCE);
        }
    }

    static SkillAction animation(JsonObject j, Consumer<String> warn) {
        String n = Json.str(j, "name", null);
        if (n == null) {
            warn.accept("\"name\" is required");
            return null;
        }
        return new Animation(n.toLowerCase(), (float) Math.max(0.05, Json.num(j, "speed", 1)));
    }

    /** Fires EXeaSkillEvent for plugin logic. */
    record Event(String tag) implements SkillAction {
        public Set<String> keys() { return Set.of("tag"); }

        public void run(ActionContext ctx) {
            Bukkit.getPluginManager().callEvent(new EXeaSkillEvent(ctx.active(), ctx.skill.id, tag, ctx.target, ctx.frame().location()));
        }
    }

    static SkillAction event(JsonObject j, Consumer<String> warn) {
        String t = Json.str(j, "tag", null);
        if (t == null) {
            warn.accept("\"tag\" is required");
            return null;
        }
        return new Event(t);
    }
}
