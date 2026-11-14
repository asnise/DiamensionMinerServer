package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §1.3: block pieces thrown out of the anchor. Moved on the server with gravity and a simple floor
 * bounce, drawn as interpolated BlockDisplays (the world is never changed), then shrunk away.
 */
public final class DebrisLayer {

    private DebrisLayer() { }

    private static final int SHRINK_TICKS = 5;

    record Template(List<String> blocks, int count, Json.Range size, double radius, Json.Range speed, double angle,
                    String direction, double cone, double gravity, double bounce, double spin, Json.Range lifetime, Color glow)
            implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("block", "count", "size", "radius", "speed", "angle", "direction", "cone", "gravity", "bounce", "spin", "lifetime", "glow"); }
        public double extraLife(EffectDefinition.Layer l) { return lifetime.max() + SHRINK_TICKS / 20.0; }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        String dir = Json.str(j, "direction", "outward");
        if (!Set.of("outward", "forward", "up").contains(dir)) {
            warn.accept("direction must be outward, forward or up; using outward");
            dir = "outward";
        }
        return new Template(Json.strings(j, "block", "ground"), Math.max(1, (int) Json.num(j, "count", 8)),
                Json.range(j, "size", 0.2, 0.45), Json.num(j, "radius", 0.5), Json.range(j, "speed", 0.3, 0.6),
                Json.num(j, "angle", 60), dir, Json.num(j, "cone", 60), Json.num(j, "gravity", 0.08),
                Math.max(0, Math.min(1, Json.num(j, "bounce", 0.3))), Json.num(j, "spin", 20),
                Json.range(j, "lifetime", 1.0, 1.5), Json.color(j, "glow"));
    }

    private static final class Piece {
        BlockDisplay d;
        Vector pos, vel;
        Vector3f axis;
        float angle, spin, size;
        int life, shrinkAt = -1;
        boolean resting;
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final List<Piece> pieces = new ArrayList<>();

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            double s = scale();
            var r = effect.random;
            for (int i = 0; i < t.count; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = t.radius * s * Math.sqrt(r.nextDouble());
                Location at = f.toWorld(d * Math.cos(a), 0.05, d * Math.sin(a));
                BlockData block = ParticleData.blockData(t.blocks, f, r);
                Piece p = new Piece();
                p.size = (float) (t.size.roll(r) * s);
                p.axis = new Vector3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()).normalize();
                p.spin = (float) Math.toRadians((r.nextDouble() * 2 - 1) * t.spin);
                p.angle = (float) (r.nextDouble() * Math.PI * 2);
                p.life = (int) Math.round(t.lifetime.roll(r) * 20);
                p.pos = at.toVector();
                p.vel = launch(f, d * Math.cos(a), d * Math.sin(a), r).multiply(t.speed.roll(r) * s);
                Quaternionf q = new Quaternionf().fromAxisAngleRad(p.axis, p.angle);
                p.d = EffectManager.get().spawnDisplay(effect, at, BlockDisplay.class, bd -> {
                    bd.setBlock(block);
                    bd.setTransformation(Pieces.cube(p.size, q));
                    bd.setTeleportDuration(1);
                    if (t.glow != null) {
                        bd.setGlowing(true);
                        bd.setGlowColorOverride(t.glow);
                    }
                });
                if (p.d == null) return; // over budget
                pieces.add(p);
            }
        }

        /** Unit launch direction: outward from the centre (or forward / up), tilted up by {@code angle}. */
        private Vector launch(Frame f, double lx, double lz, java.util.Random r) {
            double elev = Math.toRadians(Math.max(0, Math.min(90, t.angle + (r.nextDouble() * 30 - 15))));
            Vector flat;
            switch (t.direction) {
                case "up" -> {
                    return new Vector((r.nextDouble() - 0.5) * 0.2, 1, (r.nextDouble() - 0.5) * 0.2).normalize();
                }
                case "forward" -> {
                    double a = Math.toRadians((r.nextDouble() - 0.5) * t.cone);
                    flat = f.dir(Math.sin(a), 0, Math.cos(a));
                }
                default -> {
                    flat = f.dir(lx, 0, lz);
                    if (flat.lengthSquared() < 1e-6) {
                        double a = r.nextDouble() * Math.PI * 2;
                        flat = new Vector(Math.cos(a), 0, Math.sin(a));
                    }
                }
            }
            flat.normalize().multiply(Math.cos(elev));
            return flat.setY(Math.sin(elev));
        }

        @Override
        protected boolean update() {
            for (Iterator<Piece> it = pieces.iterator(); it.hasNext(); ) {
                Piece p = it.next();
                if (p.d == null || !p.d.isValid()) {
                    EffectManager.get().release(effect, p.d); // gone with its chunk: free its budget
                    it.remove();
                    continue;
                }
                p.life--;
                if (p.shrinkAt < 0 && p.life <= 0) {
                    Pieces.shrink(p.d, SHRINK_TICKS);
                    p.shrinkAt = SHRINK_TICKS;
                }
                if (p.shrinkAt >= 0 && p.shrinkAt-- <= 0) {
                    EffectManager.get().release(effect, p.d);
                    it.remove();
                    continue;
                }
                if (!p.resting) move(p);
            }
            return !pieces.isEmpty();
        }

        private void move(Piece p) {
            World w = p.d.getWorld();
            p.vel.setY(p.vel.getY() - t.gravity);
            Vector next = p.pos.clone().add(p.vel);
            if (p.vel.getY() < 0) {
                Double floor = Frame.groundY(w, next.getX(), p.pos.getY(), next.getZ(), (int) Math.ceil(-p.vel.getY()) + 1);
                double bottom = next.getY() - p.size / 2;
                if (floor != null && bottom <= floor) {
                    next.setY(floor + p.size / 2);
                    p.vel.setY(-p.vel.getY() * t.bounce);
                    p.vel.setX(p.vel.getX() * 0.6);
                    p.vel.setZ(p.vel.getZ() * 0.6);
                    p.spin *= 0.6f;
                    if (Math.abs(p.vel.getY()) < 0.05 && p.vel.clone().setY(0).lengthSquared() < 0.0025) p.resting = true;
                }
            }
            p.pos = next;
            p.angle += p.spin;
            p.d.teleport(new Location(w, next.getX(), next.getY(), next.getZ()));
            if (p.spin != 0) {
                p.d.setInterpolationDelay(0);
                p.d.setInterpolationDuration(1);
                p.d.setTransformation(Pieces.cube(p.size, new Quaternionf().fromAxisAngleRad(p.axis, p.angle)));
            }
        }

        @Override
        public void end() {
            for (Piece p : pieces) EffectManager.get().release(effect, p.d);
            pieces.clear();
        }
    }
}
