package com.exea.entity.core.skill;

import com.exea.entity.api.SpawnOptions;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;

import java.util.Set;
import java.util.function.Consumer;

/**
 * §2.4 {@code summon}: spawns other EXea models around the action's place. They target the caster's
 * target. With {@code bound} (default) they are removed when the summoner is removed.
 */
record SummonAction(String model, int count, double radius, int maxAlive, boolean bound, JsonObject options) implements SkillAction {

    public Set<String> keys() { return Set.of("model", "count", "radius", "max_alive", "bound", "options"); }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        String m = Json.str(j, "model", null);
        if (m == null) {
            warn.accept("\"model\" is required");
            return null;
        }
        return new SummonAction(m.toLowerCase(), Math.max(1, (int) Json.num(j, "count", 1)), Json.num(j, "radius", 3),
                (int) Json.num(j, "max_alive", 0), Json.bool(j, "bound", true), Json.obj(j, "options"));
    }

    public void run(ActionContext ctx) {
        if (!EntityManager.getInstance().getTemplateIds().contains(model)) {
            SkillManager.get().warnOnce("summon:" + model, "skill " + ctx.skill.id + ": summon of unknown model \"" + model + "\"");
            return;
        }
        Frame f = ctx.frame();
        for (int i = 0; i < count; i++) {
            if (maxAlive > 0 && ctx.caster.aliveSummons(model) >= maxAlive) return;
            double a = ctx.random.nextDouble() * Math.PI * 2, d = radius * Math.sqrt(ctx.random.nextDouble());
            Location at = f.toWorld(d * Math.cos(a), 0, d * Math.sin(a));
            Double gy = Frame.groundY(at.getWorld(), at.getX(), at.getY() + 2, at.getZ(), 6);
            if (gy != null) at.setY(gy);
            at.setYaw(f.yaw);
            EXActiveEntity s = EntityManager.getInstance().spawn(model, at, options(options));
            if (s == null) continue;
            if (s.getRootEntity() instanceof Mob mob && ctx.target != null) mob.setTarget(ctx.target);
            ctx.caster.addSummon(s, bound);
        }
    }

    static SpawnOptions options(JsonObject o) {
        SpawnOptions s = SpawnOptions.create();
        if (o == null) return s;
        String driver = Json.str(o, "driver", null);
        if (driver != null) {
            NamespacedKey k = NamespacedKey.fromString(Json.key(driver));
            EntityType t = k == null ? null : Registry.ENTITY_TYPE.get(k);
            if (t != null && t.isAlive()) s.driver(t);
        }
        if (o.has("ai")) s.ai(Json.bool(o, "ai", true));
        Double v;
        if ((v = Json.numOrNull(o, "health")) != null) s.health(v);
        if ((v = Json.numOrNull(o, "attack_damage")) != null) s.attackDamage(v);
        if ((v = Json.numOrNull(o, "movement_speed")) != null) s.movementSpeed(v);
        if ((v = Json.numOrNull(o, "hitbox_scale")) != null) s.hitboxScale(v);
        if ((v = Json.numOrNull(o, "follow_range")) != null) s.followRange(v);
        String name = Json.str(o, "display_name", null);
        if (name != null) s.displayName(name);
        if (o.has("name_visible")) s.nameVisible(Json.bool(o, "name_visible", true));
        if (o.has("auto_skills")) s.autoSkills(Json.bool(o, "auto_skills", true));
        return s;
    }
}
