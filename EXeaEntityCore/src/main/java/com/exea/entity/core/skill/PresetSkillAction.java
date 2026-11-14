package com.exea.entity.core.skill;

import com.exea.entity.core.effect.Json;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.Consumer;

/**
 * §2.4 {@code preset_skill}: runs another skill's actions from its time {@code from} on, inside this
 * cast, without its animation or cooldown. A leap that ends in the slam skill's impact uses it.
 */
record PresetSkillAction(String skill, double from) implements SkillAction {

    public Set<String> keys() { return Set.of("skill", "from"); }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        String s = Json.str(j, "skill", null);
        if (s == null) {
            warn.accept("\"skill\" is required");
            return null;
        }
        return new PresetSkillAction(s, Math.max(0, Json.num(j, "from", 0)));
    }

    public void run(ActionContext ctx) {
        Moveset.Skill other = ctx.caster.moveset.skills.get(skill);
        if (other != null && ctx.cast != null && other != ctx.skill) ctx.cast.insert(other, from);
    }
}
