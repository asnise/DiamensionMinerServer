package com.exea.entity.core.effect;

/**
 * One running layer. The base class handles the timing: {@link #emit} once (burst), or every
 * {@code interval} while {@code duration} runs, with progress 0..1. Layers that own entities keep
 * them alive in {@link #update} and remove them in {@link #end}.
 */
public abstract class LayerRun {

    protected final EffectInstance effect;
    protected final EffectDefinition.Layer layer;
    protected int age;
    private int nextEmit;
    private final int durationTicks;
    private final int intervalTicks;
    private boolean emitting = true;
    private Frame fixedFrame;

    protected LayerRun(EffectInstance effect, EffectDefinition.Layer layer) {
        this.effect = effect;
        this.layer = layer;
        this.durationTicks = (int) Math.round(layer.duration * 20);
        this.intervalTicks = Math.max(1, (int) Math.round(layer.interval * 20));
    }

    /** One tick. False once the layer is done and owns nothing any more. */
    public final boolean tick() {
        if (emitting && age >= nextEmit) {
            double progress = durationTicks <= 0 ? 1 : Math.min(1, (double) age / durationTicks);
            try {
                emit(progress);
            } catch (RuntimeException e) {
                EffectManager.get().warnOnce(effect.def.id + "/" + layer.type, "layer " + layer.type + " failed: " + e);
                emitting = false;
            }
            nextEmit += intervalTicks;
        }
        if (age >= durationTicks || effect.isStopping()) emitting = false;
        boolean alive = update();
        age++;
        return emitting || alive;
    }

    /** Spawn this layer's particles / pieces / sound at {@code progress} (0..1 through its duration). */
    protected abstract void emit(double progress);

    /** Moves owned things; true while any are alive. */
    protected boolean update() {
        return false;
    }

    /** Removes everything this layer owns, now. */
    public void end() { }

    /** Where the layer is: recomputed each call when the anchor follows, otherwise fixed at the first call. */
    protected Frame frame() {
        if (!layer.anchor.follow() && fixedFrame != null) return fixedFrame;
        Frame f = effect.frame(layer.anchor);
        if (f != null) fixedFrame = f;
        return fixedFrame;
    }

    protected double scale() {
        return effect.scale;
    }
}
