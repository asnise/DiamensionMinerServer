package com.exea.entity.api.skill;

import com.exea.entity.api.EXeaEntity;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** What a plugin-registered skill action gets when it runs. */
public interface SkillActionContext {

    /** The entity casting the skill. */
    EXeaEntity getCaster();

    String getSkillId();

    /** The skill's target, or null. */
    LivingEntity getTarget();

    /** The action's place: its bone or anchor, facing the caster's body yaw (or the impact point in on_hit). */
    Location getLocation();

    /** The action's JSON as written in the moveset (read your own keys). Do not modify. */
    JsonObject getJson();
}