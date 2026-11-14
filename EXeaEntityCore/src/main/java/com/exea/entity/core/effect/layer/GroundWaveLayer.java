package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * §1.4: a ring of ground blocks pops up and settles as it travels outward. Each popped block is a
 * BlockDisplay copy of the real block, lifted and lowered with interpolation (the world is untouched).
 */
public final class GroundWaveLayer {

    private GroundWaveLayer() { }

    private static final int UP_TICKS = 3, DOWN_TICKS = 4;

    record Template(Json.Range radius, double height, List<String> blocks, double density, double angle) implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) { return new Run(e, l, this); }
        public Set<String> keys() { return Set.of("radius", "height", "block", "density", "angle"); }
        public double extraLife(EffectDefinition.Layer l) { return (UP_TICKS + DOWN_TICKS + 2) / 20.0; }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        if (Json.num(j, "duration", 0) <= 0) warn.accept("needs a duration > 0; using 0.5");
        return new Template(Json.range(j, "radius", 1, 5), Json.num(j, "height", 0.5), Json.strings(j, "block", "ground"),
                Math.max(0.1, Json.num(j, "density", 1)), Math.max(1, Math.min(360, Json.num(j, "angle", 360))));
    }

    private static final class Pop {
        BlockDisplay d;
        int age;
    }

    static final class Run extends LayerRun {
        private final Template t;
        private final Set<Long> done = new HashSet<>();
        private final List<Pop> pops = new ArrayList<>();

        Run(EffectInstance e, EffectDefinition.Layer l, Template t) {
            super(e, l);
            this.t = t;
        }

        @Override
        protected void emit(double progress) {
            Frame f = frame();
            if (f == null) return;
            double s = scale();
            double r = (t.radius.min() + (t.radius.max() - t.radius.min()) * progress) * s;
            double sector = Math.toRadians(t.angle);
            int n = Math.max(4, (int) Math.ceil(r * sector * t.density));
            for (int i = 0; i < n; i++) {
                double a = -sector / 2 + sector * (i + 0.5) / n;
                // a = 0 is straight ahead (local +Z)
                Location p = f.toWorld(r * Math.sin(a), 0, r * Math.cos(a));
                Block g = Frame.groundBlock(f.world, p.getX(), p.getY() + 1.5, p.getZ(), 4);
                if (g == null || !Frame.solid(g)) continue;
                Block above = g.getRelative(0, 1, 0);
                if (Frame.solid(above)) continue; // buried, nothing to see
                long key = ((long) g.getX() & 0x3FFFFFF) << 38 | ((long) g.getZ() & 0x3FFFFFF) << 12 | (g.getY() & 0xFFF);
                if (!done.add(key)) continue;
                BlockData data = t.blocks.size() == 1 && "ground".equals(t.blocks.get(0))
                        ? g.getBlockData() : ParticleData.blockData(t.blocks, f.at(g.getLocation().add(0.5, 1, 0.5)), effect.random);
                Pop pop = new Pop();
                pop.d = EffectManager.get().spawnDisplay(effect, g.getLocation(), BlockDisplay.class, bd -> {
                    bd.setBlock(data);
                    // a hair bigger than the real block, so it is drawn over it while raised
                    bd.setTransformation(new Transformation(new Vector3f(-0.005f, 0, -0.005f), new Quaternionf(),
                            new Vector3f(1.01f, 1.01f, 1.01f), new Quaternionf()));
                });
                if (pop.d == null) return;
                pops.add(pop);
            }
        }

        @Override
        protected boolean update() {
            float h = (float) (t.height * scale());
            for (Iterator<Pop> it = pops.iterator(); it.hasNext(); ) {
                Pop p = it.next();
                if (p.d == null || !p.d.isValid()) {
                    EffectManager.get().release(effect, p.d);
                    it.remove();
                    continue;
                }
                if (p.age == 1) move(p, h, UP_TICKS);
                else if (p.age == 1 + UP_TICKS) move(p, 0, DOWN_TICKS);
                else if (p.age >= 2 + UP_TICKS + DOWN_TICKS) {
                    EffectManager.get().release(effect, p.d);
                    it.remove();
                    continue;
                }
                p.age++;
            }
            return !pops.isEmpty();
        }

        private static void move(Pop p, float y, int ticks) {
            Transformation tr = p.d.getTransformation();
            p.d.setInterpolationDelay(0);
            p.d.setInterpolationDuration(ticks);
            p.d.setTransformation(new Transformation(new Vector3f(-0.005f, y, -0.005f), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
        }

        @Override
        public void end() {
            for (Pop p : pops) EffectManager.get().release(effect, p.d);
            pops.clear();
        }
    }
}
