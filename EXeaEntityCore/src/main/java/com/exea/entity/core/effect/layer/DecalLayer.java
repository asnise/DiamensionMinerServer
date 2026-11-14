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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** §1.5: thin plates on the ground (cracks, scorch marks, a circle) that stay, then shrink away. */
public final class DecalLayer {

    private DecalLayer() { }

    private static final int SHRINK_TICKS = 6;

    record Template(List<String> blocks, String pattern, double radius, int pieces, double lifetime, Color glow) implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("block", "pattern", "radius", "pieces", "lifetime", "glow"); }
        public double extraLife(EffectDefinition.Layer l) { return lifetime + SHRINK_TICKS / 20.0; }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        String pattern = Json.str(j, "pattern", "crack");
        if (!Set.of("crack", "scorch", "circle").contains(pattern)) {
            warn.accept("pattern must be crack, scorch or circle; using crack");
            pattern = "crack";
        }
        return new Template(Json.strings(j, "block", "black_concrete"), pattern, Json.num(j, "radius", 2),
                Math.max(1, (int) Json.num(j, "pieces", 12)), Json.num(j, "lifetime", 4), Json.color(j, "glow"));
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final List<BlockDisplay> plates = new ArrayList<>();
        private int left = -1, shrink = -1;

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            double s = scale(), rad = t.radius * s;
            Random r = effect.random;
            List<double[]> spots = new ArrayList<>(); // x, z, size, yaw
            switch (t.pattern) {
                case "circle" -> {
                    for (int i = 0; i < t.pieces; i++) {
                        double a = Math.PI * 2 * i / t.pieces;
                        spots.add(new double[]{rad * Math.cos(a), rad * Math.sin(a), Math.max(0.25, Math.PI * 2 * rad / t.pieces * 1.1), -a});
                    }
                }
                case "scorch" -> {
                    for (int i = 0; i < t.pieces; i++) {
                        double a = r.nextDouble() * Math.PI * 2, d = rad * Math.sqrt(r.nextDouble());
                        spots.add(new double[]{d * Math.cos(a), d * Math.sin(a), (0.4 + r.nextDouble() * 0.5) * s, r.nextDouble() * Math.PI});
                    }
                }
                default -> {
                    // cracks: 3-5 branches walking outward from the centre with a little wander
                    int branches = 3 + r.nextInt(3), per = Math.max(1, t.pieces / branches);
                    for (int b = 0; b < branches; b++) {
                        double a = Math.PI * 2 * b / branches + r.nextDouble() * 0.6, x = 0, z = 0, step = rad / per;
                        for (int i = 0; i < per; i++) {
                            a += (r.nextDouble() - 0.5) * 0.8;
                            x += Math.cos(a) * step;
                            z += Math.sin(a) * step;
                            spots.add(new double[]{x, z, step * 1.15, -a});
                        }
                    }
                }
            }
            for (double[] sp : spots) {
                Location at = f.toWorld(sp[0], 0, sp[1]);
                Double gy = Frame.groundY(f.world, at.getX(), at.getY() + 1, at.getZ(), 3);
                if (gy == null) continue;
                at.setY(gy + 0.005 + r.nextDouble() * 0.004);
                BlockData data = ParticleData.blockData(t.blocks, f, r);
                float w = (float) sp[2], yaw = (float) (sp[3] + Math.toRadians(f.yaw));
                BlockDisplay d = EffectManager.get().spawnDisplay(effect, at, BlockDisplay.class, bd -> {
                    bd.setBlock(data);
                    bd.setTransformation(Pieces.plate(w, 0.02f, yaw));
                    if (t.glow != null) {
                        bd.setBrightness(new Display.Brightness(15, 15));
                        bd.setGlowing(true);
                        bd.setGlowColorOverride(t.glow);
                    }
                });
                if (d == null) break;
                plates.add(d);
            }
            left = (int) Math.round(t.lifetime * 20);
        }

        @Override
        protected boolean update() {
            if (plates.isEmpty()) return false;
            if (left > 0) left--;
            else if (shrink < 0) {
                for (BlockDisplay d : plates) if (d.isValid()) Pieces.shrink(d, SHRINK_TICKS);
                shrink = SHRINK_TICKS;
            } else if (shrink-- <= 0) {
                end();
                return false;
            }
            return true;
        }

        @Override
        public void end() {
            for (BlockDisplay d : plates) EffectManager.get().release(effect, d);
            plates.clear();
        }
    }
}
