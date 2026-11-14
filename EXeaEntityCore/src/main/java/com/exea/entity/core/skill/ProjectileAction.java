package com.exea.entity.core.skill;

import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.Origin;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * §2.5 {@code projectile}: server-side projectiles (no vanilla entity): a point that flies with speed,
 * gravity and homing, drawn by its trail effect (and an optional item model), hitting the first living
 * thing within {@code radius} or a solid block, then running {@code on_hit} at the impact.
 */
record ProjectileAction(double speed, double gravity, double lifetime, double radius, String aim, double homing, int count,
                        double spread, int pierce, NamespacedKey itemModel, String trail, double damage,
                        List<Moveset.ActionSpec> onHit) implements SkillAction {

    public Set<String> keys() {
        return Set.of("speed", "gravity", "lifetime", "radius", "aim", "homing", "count", "spread", "pierce", "item_model", "trail", "damage", "on_hit");
    }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        String aim = Json.str(j, "aim", "target");
        if (!Set.of("target", "forward", "target_ground").contains(aim)) {
            warn.accept("aim must be target, forward or target_ground; using target");
            aim = "target";
        }
        String im = Json.str(j, "item_model", null);
        List<Moveset.ActionSpec> onHit = Moveset.parseActions(j.get("on_hit"), "on_hit", warn);
        for (Moveset.ActionSpec a : onHit) {
            if (a.action instanceof ProjectileAction || a.action instanceof MoveAction) warn.accept("on_hit: " + a.type + " actions are not run inside on_hit");
        }
        return new ProjectileAction(Math.max(0.05, Json.num(j, "speed", 0.8)), Json.num(j, "gravity", 0), Math.max(0.05, Json.num(j, "lifetime", 3)),
                Math.max(0.1, Json.num(j, "radius", 0.5)), aim, Math.max(0, Math.min(1, Json.num(j, "homing", 0))),
                Math.max(1, (int) Json.num(j, "count", 1)), Json.num(j, "spread", 0), Math.max(0, (int) Json.num(j, "pierce", 0)),
                im == null ? null : NamespacedKey.fromString(im.toLowerCase()), Json.str(j, "trail", "projectile_trail"),
                Math.max(0, Json.num(j, "damage", 0)),
                onHit.stream().filter(a -> !(a.action instanceof ProjectileAction) && !(a.action instanceof MoveAction)).toList());
    }

    public void run(ActionContext ctx) {
        Frame f = ctx.frame();
        Location start = f.location();
        Vector base = aimDirection(ctx, start);
        for (int i = 0; i < count; i++) {
            double turn = count == 1 ? 0 : Math.toRadians(-spread / 2 + spread * i / (count - 1));
            Vector v = base.clone().rotateAroundY(-turn);
            if (!"target_ground".equals(aim)) v.normalize().multiply(speed);
            ctx.caster.run(new Flight(ctx, start.clone(), v));
        }
    }

    /** Initial velocity direction; for target_ground a ballistic velocity that lands on the target's feet. */
    private Vector aimDirection(ActionContext ctx, Location start) {
        LivingEntity t = ctx.target;
        boolean hasTarget = t != null && t.isValid() && t.getWorld() == start.getWorld();
        if ("target_ground".equals(aim) && hasTarget) {
            Vector to = t.getLocation().toVector().subtract(start.toVector());
            double flat = Math.hypot(to.getX(), to.getZ());
            double time = Math.max(4, flat / speed);
            double vy = (to.getY() + 0.5 * gravity * time * time) / time;
            return new Vector(to.getX() / time, vy, to.getZ() / time);
        }
        if (hasTarget && !"forward".equals(aim)) {
            Vector to = t.getLocation().add(0, t.getHeight() / 2, 0).toVector().subtract(start.toVector());
            if (to.lengthSquared() > 1e-6) return to;
        }
        Vector fwd = new Frame(start, ctx.driver().getBodyYaw()).forward();
        return "target_ground".equals(aim) ? fwd.multiply(speed).setY(0.3) : fwd;
    }

    /** One flying projectile. */
    private final class Flight implements SkillManager.Task {
        private final ActionContext ctx;
        private final Location pos;
        private Vector vel;
        private int life;
        private int pierced;
        private final Set<UUID> hit = new HashSet<>();
        private final EffectInstance trailFx;
        private final ItemDisplay display;

        Flight(ActionContext ctx, Location pos, Vector vel) {
            this.ctx = ctx;
            this.pos = pos;
            this.vel = vel;
            this.life = (int) Math.round(lifetime * 20);
            Origin o = new Origin() {
                protected Location compute() { return pos.clone(); }
                protected float computeYaw() { return (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ())); }
                public boolean valid() { return life > 0; }
            };
            this.trailFx = trail == null || trail.isBlank() ? null : EffectManager.get().play(trail, o, EffectOptions.create());
            this.display = itemModel == null ? null : pos.getWorld().spawn(pos, ItemDisplay.class, d -> {
                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta m = item.getItemMeta();
                m.setItemModel(itemModel);
                item.setItemMeta(m);
                d.setItemStack(item);
                d.setPersistent(false);
                d.addScoreboardTag(EffectManager.TAG);
                d.setTeleportDuration(1);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
            });
        }

        public boolean tick() {
            if (--life < 0) return end(null);
            World w = pos.getWorld();
            LivingEntity t = ctx.target;
            if (homing > 0 && t != null && t.isValid() && t.getWorld() == w) {
                Vector want = t.getLocation().add(0, t.getHeight() / 2, 0).toVector().subtract(pos.toVector());
                if (want.lengthSquared() > 1e-6) {
                    double sp = vel.length();
                    vel = vel.clone().normalize().multiply(1 - homing).add(want.normalize().multiply(homing)).normalize().multiply(sp);
                }
            }
            vel.setY(vel.getY() - gravity);
            // move in small steps so fast projectiles don't skip through thin walls or targets
            double len = vel.length();
            int steps = Math.max(1, (int) Math.ceil(len / 0.5));
            Vector step = vel.clone().multiply(1.0 / steps);
            for (int i = 0; i < steps; i++) {
                pos.add(step);
                if (Frame.solid(w.getBlockAt(pos))) return end(pos.clone().subtract(step));
                for (Entity e : w.getNearbyEntities(pos, radius + 1, radius + 1, radius + 1)) {
                    if (!ActionContext.canHit(ctx.driver(), e) || hit.contains(e.getUniqueId())) continue;
                    LivingEntity le = (LivingEntity) e;
                    if (!le.getBoundingBox().expand(radius).contains(pos.toVector())) continue;
                    hit.add(le.getUniqueId());
                    ctx.damage(le, damage, null);
                    impact(pos.clone(), le);
                    if (++pierced > pierce) return end(null, false);
                }
            }
            if (display != null && display.isValid()) {
                Location l = pos.clone();
                l.setDirection(vel);
                display.teleport(l);
            }
            return true;
        }

        private boolean end(Location blockImpact) {
            return end(blockImpact, blockImpact != null);
        }

        private boolean end(Location at, boolean runOnHit) {
            if (runOnHit && at != null) impact(at, null);
            stop();
            return false;
        }

        private void impact(Location at, LivingEntity victim) {
            at.setYaw((float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ())));
            for (Moveset.ActionSpec a : onHit) {
                ActionContext c = new ActionContext(ctx.caster, ctx.skill, null, victim != null ? victim : ctx.target, a, at, ctx.random);
                ctx.caster.runAction(a, c);
            }
        }

        public void stop() {
            life = 0;
            if (trailFx != null) trailFx.stop();
            if (display != null) display.remove();
        }
    }
}
