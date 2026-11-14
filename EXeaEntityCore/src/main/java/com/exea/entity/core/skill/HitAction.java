package com.exea.entity.core.skill;

import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.Shape;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §2.4 {@code hit}: damages living entities inside a shape around the action's place. Flat shapes
 * (circle, cone, ring, line) count targets within {@code height} blocks above or below the place.
 */
record HitAction(Shape shape, double damage, double knockback, double knockup, int maxTargets, List<PotionEffect> potions,
                 String tag, boolean once, double height) implements SkillAction {

    public Set<String> keys() { return Set.of("shape", "damage", "knockback", "knockup", "max_targets", "potions", "tag", "once", "height"); }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        Shape s = Shape.parse(Json.obj(j, "shape"));
        if (s == null || !j.has("shape")) {
            warn.accept("needs a shape");
            return null;
        }
        List<PotionEffect> potions = new ArrayList<>();
        if (j.has("potions") && j.get("potions").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("potions")) {
                if (!e.isJsonObject()) continue;
                JsonObject p = e.getAsJsonObject();
                NamespacedKey k = NamespacedKey.fromString(Json.key(Json.str(p, "type", "")));
                PotionEffectType type = k == null ? null : Registry.MOB_EFFECT.get(k);
                if (type == null) {
                    warn.accept("unknown potion \"" + Json.str(p, "type", "") + "\"");
                    continue;
                }
                potions.add(new PotionEffect(type, (int) Math.round(Json.num(p, "seconds", 3) * 20), (int) Json.num(p, "amplifier", 0)));
            }
        }
        return new HitAction(s, Json.num(j, "damage", 0), Json.num(j, "knockback", 0), Json.num(j, "knockup", 0),
                (int) Json.num(j, "max_targets", 0), List.copyOf(potions), Json.str(j, "tag", null),
                Json.bool(j, "once", false), Json.num(j, "height", 3));
    }

    public void run(ActionContext ctx) {
        Frame f = ctx.frame();
        LivingEntity caster = ctx.driver();
        List<LivingEntity> hits = targets(f, caster, shape, height);
        if (maxTargets > 0 && hits.size() > maxTargets) {
            Vector c = new Vector(f.x, f.y, f.z);
            hits.sort(Comparator.comparingDouble(t -> t.getLocation().toVector().distanceSquared(c)));
            hits = hits.subList(0, maxTargets);
        }
        for (LivingEntity t : hits) {
            if (once && ctx.cast != null && !ctx.cast.markHit(ctx.spec, t)) continue;
            ctx.damage(t, damage, tag);
            if (knockback != 0 || knockup != 0) {
                Vector away = t.getLocation().toVector().subtract(new Vector(f.x, f.y, f.z)).setY(0);
                if (away.lengthSquared() < 1e-6) away = f.forward();
                Vector v = away.normalize().multiply(knockback);
                v.setY(knockup != 0 ? knockup : (knockback > 0 ? 0.25 : 0));
                t.setVelocity(t.getVelocity().add(v));
            }
            for (PotionEffect p : potions) t.addPotionEffect(p);
        }
    }

    /** Living things the caster may hit whose body is inside the shape (feet, middle or head). */
    static List<LivingEntity> targets(Frame f, LivingEntity caster, Shape shape, double height) {
        double reach = Math.max(1, Math.max(shape.radius(0, 1), shape.length(0, 1))) + 2 + height;
        List<LivingEntity> out = new ArrayList<>();
        org.bukkit.Location at = f.location();
        for (Entity e : f.world.getNearbyEntities(at, reach, reach, reach)) {
            if (!ActionContext.canHit(caster, e)) continue;
            LivingEntity t = (LivingEntity) e;
            double h = t.getHeight();
            for (double dy : new double[]{0, h / 2, h * 0.9}) {
                double[] l = toLocal(f, t.getX(), t.getY() + dy, t.getZ());
                if (shape.contains(l[0], l[1], l[2], 0, 1, height)) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    /** World point to the frame's local [right, up, forward]. */
    static double[] toLocal(Frame f, double x, double y, double z) {
        double dx = x - f.x, dz = z - f.z;
        double r = Math.toRadians(f.yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
        return new double[]{dx * rx + dz * rz, y - f.y, dx * fx + dz * fz};
    }
}
