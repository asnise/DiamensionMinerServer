package com.exea.entity.core.effect;

import com.exea.entity.api.effect.EffectHandle;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** One play of an effect: starts its layers on time, ticks them, ends when done or stopped. */
public final class EffectInstance implements EffectHandle {

    /** Hard limit after the effect's duration, so a broken layer can't keep entities forever. */
    private static final int OVERRUN_TICKS = 20 * 30;

    public final EffectDefinition def;
    public final Origin origin;
    public final double scale;
    public final Entity target;
    public final Set<String> tags;
    public final int depth;
    public final Random random = new Random();
    private final List<LayerRun> runs = new ArrayList<>();
    private final boolean[] started;
    private int ticks;
    private int cycleTicks;
    private boolean stopping;
    private boolean done;
    /** Display entities this effect owns right now (budget). */
    int entities;

    EffectInstance(EffectDefinition def, Origin origin, double scale, Entity target, Set<String> tags, int depth) {
        this.def = def;
        this.origin = origin;
        this.scale = scale;
        this.target = target;
        this.tags = tags;
        this.depth = depth;
        this.started = new boolean[def.layers.size()];
    }

    @Override
    public String getId() {
        return def.id;
    }

    @Override
    public boolean isActive() {
        return !done;
    }

    @Override
    public void stop() {
        if (done) return;
        for (LayerRun r : runs) r.end();
        runs.clear();
        done = true;
    }

    boolean isStopping() {
        return stopping;
    }

    /** One server tick. False when finished. */
    boolean tick() {
        if (done) return false;
        // The thing it plays on is gone: start nothing new, let what is running finish.
        if (!origin.valid()) stopping = true;
        double t = cycleTicks / 20.0;
        if (!stopping) {
            for (int i = 0; i < started.length; i++) {
                if (started[i]) continue;
                EffectDefinition.Layer l = def.layers.get(i);
                if (t + 1e-6 < l.start) continue;
                started[i] = true;
                if (l.chance < 1 && random.nextDouble() >= l.chance) continue;
                if (l.when != null && !tags.contains(l.when)) continue;
                LayerRun run = EffectManager.get().startLayer(this, l);
                if (run != null) runs.add(run);
            }
        }
        for (Iterator<LayerRun> it = runs.iterator(); it.hasNext(); ) {
            LayerRun r = it.next();
            if (!r.tick()) {
                r.end();
                it.remove();
            }
        }
        ticks++;
        cycleTicks++;
        double dur = def.duration * 20;
        if (def.loop && !stopping && cycleTicks >= dur) {
            cycleTicks = 0;
            java.util.Arrays.fill(started, false);
        }
        boolean finished = (stopping || !def.loop) && runs.isEmpty() && (stopping || cycleTicks >= dur);
        if (finished || (!def.loop && ticks > dur + OVERRUN_TICKS)) {
            stop();
            return false;
        }
        return true;
    }

    /** Ends looping effects gracefully (layers finish their pieces). */
    public void stopLooping() {
        stopping = true;
    }

    /** The anchor's place and facing right now (§1.1), or null when there is no place. */
    public Frame frame(EffectDefinition.Anchor a) {
        Location base = null;
        if (a.atTarget() && target != null && target.isValid()) base = target.getLocation();
        if (base == null) base = origin.location();
        if (base == null || base.getWorld() == null) return null;
        float yaw = origin.yaw() + a.yaw();
        Frame f = new Frame(base, yaw);
        double[] o = a.offset();
        Location p = (o[0] != 0 || o[1] != 0 || o[2] != 0) ? f.toWorld(o[0] * scale, o[1] * scale, o[2] * scale) : base;
        if (a.ground()) {
            Double gy = Frame.groundY(p.getWorld(), p.getX(), p.getY(), p.getZ(), 8);
            if (gy != null) p.setY(gy);
        }
        return new Frame(p, yaw);
    }
}
