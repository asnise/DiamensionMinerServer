package com.exea.entity.core.skill;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A compiled action type (§2.4): checked once when the moveset loads, run each time its time comes. */
public interface SkillAction {

    void run(ActionContext ctx);

    /** Keys this type reads besides at/type/repeat/bone/anchor (for the unknown-key warning). */
    Set<String> keys();

    interface Factory {
        /** Null (after a warning) when the action can't work. */
        SkillAction compile(JsonObject json, Consumer<String> warn);
    }

    Map<String, Factory> BUILT_IN = Map.ofEntries(
            Map.entry("hit", HitAction::compile),
            Map.entry("telegraph", InlineEffectAction::telegraph),
            Map.entry("sound", InlineEffectAction::sound),
            Map.entry("fx", FxAction::compile),
            Map.entry("projectile", ProjectileAction::compile),
            Map.entry("move", MoveAction::compile),
            Map.entry("summon", SummonAction::compile),
            Map.entry("self", SelfAction::compile),
            Map.entry("animation", SimpleActions::animation),
            Map.entry("preset_skill", PresetSkillAction::compile),
            Map.entry("event", SimpleActions::event));
}
