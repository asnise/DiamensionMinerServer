package com.exea.entity.core.skill;

import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.Shape;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.function.Consumer;

/**
 * §2.6 {@code move}: dash, leap, teleport (the driver), pull and push (the targets in a shape).
 * Dash and leap run over {@code duration} as a ticking task; the mob's own movement is paused meanwhile.
 */
record MoveAction(String mode, double distance, double duration, String direction, boolean through, String to,
                  double height, String fxOut, String fxIn, double radius, double strength, Shape shape) implements SkillAction {

    public Set<String> keys() {
        return Set.of("mode", "distance", "duration", "direction", "through", "to", "height", "fx_out", "fx_in", "radius", "strength", "shape");
    }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        String mode = Json.str(j, "mode", null);
        if (mode == null || !Set.of("dash", "leap", "teleport", "pull", "push").contains(mode)) {
            warn.accept("mode must be dash, leap, teleport, pull or push");
            return null;
        }
        Shape shape = j.has("shape") ? Shape.parse(Json.obj(j, "shape")) : null;
        String to = Json.str(j, "to", mode.equals("teleport") ? "behind_target" : "target");
        String dir = Json.str(j, "direction", "forward");
        return new MoveAction(mode, Json.num(j, "distance", mode.equals("teleport") ? 2 : 6), Math.max(0, Json.num(j, "duration", mode.equals("leap") ? 0.7 : 0.3)),
                dir, Json.bool(j, "through", false), to, Json.num(j, "height", 3), Json.str(j, "fx_out", null), Json.str(j, "fx_in", null),
                Json.num(j, "radius", 5), Json.num(j, "strength", 1), shape);
    }

    public void run(ActionContext ctx) {
        switch (mode) {
            case "dash" -> dash(ctx);
            case "leap" -> leap(ctx);
            case "teleport" -> teleport(ctx);
            default -> pullPush(ctx, mode.equals("pull"));
        }
    }

    private Vector horizontalDir(ActionContext ctx, String which) {
        LivingEntity d = ctx.driver();
        Vector fwd = new Frame(d.getLocation(), d.getBodyYaw()).forward();
        if ("back".equals(which)) return fwd.multiply(-1);
        if ("target".equals(which) && ctx.target != null && ctx.target.isValid() && ctx.target.getWorld() == d.getWorld()) {
            Vector v = ctx.target.getLocation().toVector().subtract(d.getLocation().toVector()).setY(0);
            if (v.lengthSquared() > 1e-6) return v.normalize();
        }
        return fwd;
    }

    private void dash(ActionContext ctx) {
        LivingEntity d = ctx.driver();
        Vector dir = horizontalDir(ctx, direction);
        int ticks = Math.max(1, (int) Math.round(duration * 20 / ctx.caster.phaseSpeed()));
        Vector step = dir.multiply(distance / ticks);
        int[] left = {ticks};
        ctx.caster.run(new SkillManager.Task() {
            public boolean tick() {
                if (!d.isValid() || ctx.caster.active.isDying()) return false;
                Location next = d.getLocation().add(step);
                if (!through && blocked(next, d)) return false;
                d.teleport(next);
                d.setFallDistance(0);
                return --left[0] > 0;
            }
        });
    }

    private void leap(ActionContext ctx) {
        LivingEntity d = ctx.driver();
        Location start = d.getLocation();
        Location dest;
        if ("target".equals(to) && ctx.target != null && ctx.target.isValid() && ctx.target.getWorld() == d.getWorld()) {
            dest = ctx.target.getLocation();
            // land just short of the target, not on its head
            Vector back = start.toVector().subtract(dest.toVector()).setY(0);
            if (back.lengthSquared() > 1) dest.add(back.normalize().multiply(Math.min(1.5, back.length() - 0.5)));
        } else {
            dest = start.clone().add(horizontalDir(ctx, "forward").multiply(distance));
        }
        Double gy = Frame.groundY(dest.getWorld(), dest.getX(), dest.getY() + 3, dest.getZ(), 8);
        if (gy != null) dest.setY(gy);
        int ticks = Math.max(1, (int) Math.round(duration * 20 / ctx.caster.phaseSpeed()));
        int[] t = {0};
        ctx.caster.run(new SkillManager.Task() {
            public boolean tick() {
                if (!d.isValid() || ctx.caster.active.isDying()) return false;
                double k = (double) ++t[0] / ticks;
                double arc = 4 * height * k * (1 - k);
                Location p = new Location(start.getWorld(), start.getX() + (dest.getX() - start.getX()) * k,
                        start.getY() + (dest.getY() - start.getY()) * k + arc, start.getZ() + (dest.getZ() - start.getZ()) * k,
                        d.getLocation().getYaw(), d.getLocation().getPitch());
                d.teleport(p);
                d.setFallDistance(0);
                return t[0] < ticks;
            }
        });
    }

    private void teleport(ActionContext ctx) {
        LivingEntity d = ctx.driver();
        Location from = d.getLocation();
        Location dest;
        if (("behind_target".equals(to) || "target".equals(to)) && ctx.target != null && ctx.target.isValid() && ctx.target.getWorld() == d.getWorld()) {
            Location tl = ctx.target.getLocation();
            Vector look = new Frame(tl, tl.getYaw()).forward();
            dest = "behind_target".equals(to) ? tl.clone().subtract(look.multiply(distance)) : tl.clone().add(look.multiply(Math.min(distance, 1.5)));
            Vector face = tl.toVector().subtract(dest.toVector());
            dest.setDirection(face.lengthSquared() > 1e-6 ? face : look);
        } else {
            double a = ctx.random.nextDouble() * Math.PI * 2;
            dest = from.clone().add(Math.cos(a) * distance, 0, Math.sin(a) * distance);
        }
        Double gy = Frame.groundY(dest.getWorld(), dest.getX(), dest.getY() + 3, dest.getZ(), 8);
        if (gy == null) return; // no floor there
        dest.setY(gy);
        dest.setPitch(0);
        if (blocked(dest, d)) return;
        if (fxOut != null) EffectManager.get().play(fxOut, from, EffectOptions.create().yaw(d.getBodyYaw()));
        d.teleport(dest);
        d.setBodyYaw(dest.getYaw());
        if (fxIn != null) EffectManager.get().play(fxIn, dest, EffectOptions.create().yaw(dest.getYaw()));
    }

    private void pullPush(ActionContext ctx, boolean pull) {
        Frame f = ctx.frame();
        Shape s = shape != null ? shape : Shape.parse(circle(radius));
        Vector center = ctx.driver().getLocation().toVector();
        for (LivingEntity t : HitAction.targets(f, ctx.driver(), s, 3)) {
            Vector v = t.getLocation().toVector().subtract(center).setY(0);
            if (v.lengthSquared() < 1e-6) continue;
            double dist = v.length();
            v.normalize().multiply(pull ? -Math.min(strength, dist * 0.35) : strength);
            v.setY(pull ? 0.15 : 0.3);
            t.setVelocity(t.getVelocity().add(v));
        }
    }

    private static JsonObject circle(double r) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "circle");
        o.addProperty("radius", r);
        return o;
    }

    /** Would the driver's body be inside a solid block at {@code at}? */
    static boolean blocked(Location at, LivingEntity d) {
        World w = at.getWorld();
        double h = Math.max(1, d.getHeight());
        for (double y = 0.1; y < h; y += 0.9) {
            if (Frame.solid(w.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() + y), at.getBlockZ()))) return true;
        }
        return false;
    }
}
