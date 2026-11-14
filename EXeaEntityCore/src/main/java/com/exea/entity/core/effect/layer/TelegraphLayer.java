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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §1.6: a warning area on the ground that fills up over its duration, so players see where a hit lands.
 * Style "particles": dust outline + growing inner outline + sparse fill. Style "plates": glowing thin
 * glass plates per block cell, appearing from the centre outward.
 */
public final class TelegraphLayer {

    private TelegraphLayer() { }

    record Template(Shape shape, String style, Color color, boolean fill) implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("shape", "style", "color", "fill"); }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        Shape shape = Shape.parse(Json.obj(j, "shape"));
        if (shape == null || !j.has("shape")) {
            warn.accept("needs a shape (circle, ring, cone, line or box)");
            return null;
        }
        if (Json.num(j, "duration", 0) <= 0) warn.accept("needs a duration > 0 (the warning time)");
        String style = Json.str(j, "style", "particles");
        if (!Set.of("particles", "plates").contains(style)) {
            warn.accept("style must be particles or plates; using particles");
            style = "particles";
        }
        Color c = Json.color(j, "color");
        return new Template(shape, style, c != null ? c : Color.fromRGB(0xFF3030), Json.bool(j, "fill", true));
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final double[] p = new double[3];
        private final List<BlockDisplay> plates = new ArrayList<>();
        private List<double[]> cells;
        private int shown;

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            if ("plates".equals(t.style)) plates(f, progress);
            else if (age % 2 == 0 || progress >= 1) particles(f, progress);
        }

        private void particles(Frame f, double progress) {
            EffectManager m = EffectManager.get();
            List<Player> viewers = m.viewers(f.world, f.location());
            if (viewers.isEmpty()) return;
            double s = scale();
            Particle.DustOptions edge = new Particle.DustOptions(t.color, 1.1f);
            ParticleBuilder b = new ParticleBuilder(Particle.DUST).receivers(viewers).count(1).offset(0, 0, 0).extra(0).data(edge);
            // full outline, about 3 points per block
            int n = m.allowParticles(Math.max(8, (int) (t.shape.perimeter(0, s) * 3)));
            for (int i = 0; i < n; i++) {
                t.shape.outline(i, n, 0, s, p);
                b.location(f.toWorld(p[0], 0.08, p[2])).spawn();
            }
            if (!t.fill) return;
            // growing inner outline + a sparse fill inside it
            double k = Math.max(0.02, progress);
            Particle.DustOptions inner = new Particle.DustOptions(t.color, 0.8f);
            b.data(inner);
            int m2 = m.allowParticles(Math.max(6, (int) (t.shape.perimeter(0, s * k) * 3)));
            for (int i = 0; i < m2; i++) {
                t.shape.outline(i, m2, 0, s * k, p);
                b.location(f.toWorld(p[0], 0.06, p[2])).spawn();
            }
            int fill = m.allowParticles((int) Math.min(40, 4 + t.shape.perimeter(0, s) * k));
            for (int i = 0; i < fill; i++) {
                t.shape.sample(effect.random, 0, s * k, -1, p);
                b.location(f.toWorld(p[0], 0.05, p[2])).spawn();
            }
        }

        private void plates(Frame f, double progress) {
            double s = scale();
            if (cells == null) {
                cells = new ArrayList<>();
                double reach = Math.max(1, t.shape.perimeter(0, s) / 2);
                for (double x = -reach; x <= reach; x += 1) {
                    for (double z = -reach; z <= reach; z += 1) {
                        if (t.shape.contains(x, 0, z, 0, s, 1)) cells.add(new double[]{x, z, Math.hypot(x, z)});
                    }
                }
                cells.sort((a, b) -> Double.compare(a[2], b[2]));
            }
            int want = t.fill ? (int) Math.ceil(cells.size() * Math.max(0.05, progress)) : cells.size();
            while (shown < want && shown < cells.size()) {
                double[] c = cells.get(shown++);
                Location at = f.toWorld(c[0], 0, c[1]);
                Double gy = Frame.groundY(f.world, at.getX(), at.getY() + 1, at.getZ(), 3);
                if (gy == null) continue;
                at.setY(gy + 0.01);
                BlockDisplay d = EffectManager.get().spawnDisplay(effect, at, BlockDisplay.class, bd -> {
                    bd.setBlock(Material.RED_STAINED_GLASS.createBlockData());
                    bd.setTransformation(Pieces.plate(0.92f, 0.02f, (float) Math.toRadians(f.yaw)));
                    bd.setBrightness(new Display.Brightness(15, 15));
                    bd.setGlowing(true);
                    bd.setGlowColorOverride(t.color);
                });
                if (d == null) break;
                plates.add(d);
            }
        }

        @Override
        protected boolean update() {
            return false; // plates go when the warning time is over
        }

        @Override
        public void end() {
            for (BlockDisplay d : plates) EffectManager.get().release(effect, d);
            plates.clear();
        }
    }
}
