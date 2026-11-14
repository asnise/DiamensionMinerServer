package com.exea.entity.core.effect.layer;

import com.destroystokyo.paper.ParticleBuilder;
import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.exea.entity.core.effect.Shape;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** §1.2: particles in a shape. With a duration it makes fog, smoke columns, auras and trails. */
public final class ParticleLayer {

    private ParticleLayer() { }

    enum Motion { NONE, OUTWARD, UP, FORWARD, INWARD, RANDOM }

    record Template(ParticleData data, int count, Shape shape, Motion motion, double speed, double spread, boolean force)
            implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("particle", "count", "shape", "motion", "speed", "spread", "force", "color", "to_color", "size", "block", "item"); }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        ParticleData data = ParticleData.parse(j, "particle", warn);
        if (data == null) return null;
        Shape shape = Shape.parse(Json.obj(j, "shape"));
        if (shape == null) {
            warn.accept("unknown shape type");
            return null;
        }
        Motion motion;
        try {
            motion = Motion.valueOf(Json.str(j, "motion", "none").toUpperCase());
        } catch (IllegalArgumentException e) {
            warn.accept("unknown motion, using none");
            motion = Motion.NONE;
        }
        return new Template(data, Math.max(1, (int) Json.num(j, "count", 10)), shape, motion,
                Json.num(j, "speed", 0), Json.num(j, "spread", 0), Json.bool(j, "force", false));
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final double[] p = new double[3];

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            EffectManager m = EffectManager.get();
            List<Player> viewers = m.viewers(f.world, f.location());
            if (viewers.isEmpty()) return;
            int n = m.allowParticles(t.count);
            if (n == 0) return;
            double s = scale();

            // A line "to": "target" runs from the anchor to the target's middle (a beam).
            Vector beam = null;
            double beamLen = -1;
            if (t.shape.type == Shape.Type.LINE && t.shape.toTarget && effect.target != null && effect.target.isValid()) {
                Location tl = effect.target.getLocation().add(0, effect.target.getHeight() / 2, 0);
                beam = tl.toVector().subtract(new Vector(f.x, f.y, f.z));
                beamLen = beam.length();
                if (beamLen > 1e-6) beam.multiply(1 / beamLen);
                else beam = null;
            }

            ParticleBuilder b = new ParticleBuilder(t.data.particle).receivers(viewers).force(t.force);
            for (int i = 0; i < n; i++) {
                t.shape.sample(effect.random, progress, s, beam != null ? beamLen : -1, p);
                Location at;
                Vector dir;
                if (beam != null) {
                    at = new Location(f.world, f.x + beam.getX() * p[2], f.y + beam.getY() * p[2], f.z + beam.getZ() * p[2]);
                    dir = beam.clone();
                } else {
                    at = f.toWorld(p);
                    dir = direction(f);
                }
                if (t.spread > 0) {
                    double sp = t.spread * s;
                    at.add((effect.random.nextDouble() - 0.5) * 2 * sp, (effect.random.nextDouble() - 0.5) * 2 * sp, (effect.random.nextDouble() - 0.5) * 2 * sp);
                }
                b.location(at).data(t.data.data(f, effect.random));
                if (t.motion == Motion.NONE || t.speed == 0 || dir == null || dir.lengthSquared() < 1e-9) {
                    b.count(1).offset(0, 0, 0).extra(0);
                } else {
                    dir.normalize();
                    b.count(0).offset(dir.getX(), dir.getY(), dir.getZ()).extra(t.speed * s);
                }
                b.spawn();
            }
        }

        /** Direction of travel for the current sample point {@code p}. */
        private Vector direction(Frame f) {
            return switch (t.motion) {
                case NONE -> null;
                case UP -> new Vector(0, 1, 0);
                case FORWARD -> f.forward();
                case OUTWARD -> f.dir(p[0], t.shape.type == Shape.Type.SPHERE ? p[1] : 0, p[2]);
                case INWARD -> f.dir(-p[0], t.shape.type == Shape.Type.SPHERE ? -p[1] : 0, -p[2]);
                case RANDOM -> new Vector(effect.random.nextDouble() - 0.5, effect.random.nextDouble() - 0.5, effect.random.nextDouble() - 0.5);
            };
        }
    }
}
