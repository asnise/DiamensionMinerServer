package com.exea.entity.core.effect.layer;

import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Display helpers shared by the block layers. */
final class Pieces {

    private Pieces() { }

    /** A cube of edge {@code s} rotated by {@code q} about its own centre (BlockDisplay draws from its corner). */
    static Transformation cube(float s, Quaternionf q) {
        Vector3f t = q.transform(new Vector3f(-s / 2, -s / 2, -s / 2));
        return new Transformation(t, new Quaternionf(q), new Vector3f(s, s, s), new Quaternionf());
    }

    /** A flat plate {@code w} × {@code h} × {@code w} centred on x/z, turned {@code yawRad} about Y. */
    static Transformation plate(float w, float h, float yawRad) {
        Quaternionf q = new Quaternionf().rotationY(yawRad);
        Vector3f t = q.transform(new Vector3f(-w / 2, 0, -w / 2));
        return new Transformation(t, q, new Vector3f(w, h, w), new Quaternionf());
    }

    /** Shrinks to nothing over {@code ticks} (the caller removes it afterwards). */
    static void shrink(Display d, int ticks) {
        Transformation tr = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(0, 0, 0), tr.getRightRotation()));
    }
}
