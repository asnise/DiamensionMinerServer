package com.exea.entity.core.animation;

import com.exea.entity.core.timeline.TimelineEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One animation, evaluated the way Blockbench's BoneAnimator.interpolate does it.
 *
 * Every channel (position, rotation, scale) is a plain 3-vector track: rotation is kept as Euler
 * degrees and interpolated per component, like Blockbench (no quaternion slerp, so 0 to 360 spins
 * a full turn). Values are Blockbench scene space, exactly as stored in the .bbmodel.
 */
public class KeyframeTimeline {

    public enum LoopMode {
        LOOP,
        ONCE,
        HOLD
    }

    public enum Interpolation {
        LINEAR,
        CATMULLROM,
        STEP;

        public static Interpolation parse(String s) {
            if (s == null) return LINEAR;
            return switch (s.toLowerCase()) {
                case "catmullrom" -> CATMULLROM;
                case "step" -> STEP;
                default -> LINEAR; // bezier is exported as linear (the exporter warns)
            };
        }
    }

    /** A keyframe. {@code pre} is the value arriving at it, {@code post} the value leaving it (same unless split). */
    public static class Keyframe {
        final float time;
        final Vector3f pre;
        final Vector3f post;
        final Interpolation interpolation;

        public Keyframe(float time, Vector3f pre, Vector3f post, Interpolation interpolation) {
            this.time = time;
            this.pre = pre;
            this.post = post != null ? post : pre;
            this.interpolation = interpolation;
        }

        public float getTime() {
            return time;
        }
    }

    /** Blockbench treats keyframes closer than this to the playhead as "on" the keyframe. */
    private static final float EPSILON = 1f / 1200f;

    private static final Vector3f ZERO = new Vector3f();
    private static final Vector3f ONE = new Vector3f(1, 1, 1);

    // #region [Variables/Fields]
    private final String name;
    private final float length;
    private final LoopMode loopMode;
    private final Map<String, List<Keyframe>> positionTracks = new HashMap<>();
    private final Map<String, List<Keyframe>> rotationTracks = new HashMap<>();
    private final Map<String, List<Keyframe>> scaleTracks = new HashMap<>();
    private final List<TimelineEvent> events = new ArrayList<>();
    // #endregion

    // #region [Properties]
    public String getName() {
        return name;
    }

    public float getLength() {
        return length;
    }

    public LoopMode getLoopMode() {
        return loopMode;
    }

    public List<TimelineEvent> getEvents() {
        return events;
    }
    // #endregion

    // #region [Public Methods]
    public KeyframeTimeline(String name, float length, LoopMode loopMode) {
        this.name = name;
        this.length = length;
        this.loopMode = loopMode;
    }

    /** Position offset in blocks, Blockbench scene space (added to the bone's origin offset). */
    public void addPositionKeyframe(String bone, Keyframe kf) {
        add(positionTracks, bone, kf);
    }

    /** Rotation as Euler degrees, added to the bone's rest rotation (Blockbench adds them the same way). */
    public void addRotationKeyframe(String bone, Keyframe kf) {
        add(rotationTracks, bone, kf);
    }

    public void addScaleKeyframe(String bone, Keyframe kf) {
        add(scaleTracks, bone, kf);
    }

    public void addEvent(TimelineEvent event) {
        events.add(event);
    }

    /** Writes the position offset (blocks) into {@code out}; 0 when the bone has no position keys. */
    public Vector3f evaluatePosition(String bone, float time, Vector3f out) {
        return evaluate(positionTracks.get(bone), time, ZERO, out);
    }

    /** Writes the rotation offset (Euler degrees) into {@code out}; 0 when the bone has no rotation keys. */
    public Vector3f evaluateRotation(String bone, float time, Vector3f out) {
        return evaluate(rotationTracks.get(bone), time, ZERO, out);
    }

    /** Writes the scale into {@code out}; 1 when the bone has no scale keys. */
    public Vector3f evaluateScale(String bone, float time, Vector3f out) {
        return evaluate(scaleTracks.get(bone), time, ONE, out);
    }
    // #endregion

    // #region [Private Methods]
    private static void add(Map<String, List<Keyframe>> tracks, String bone, Keyframe kf) {
        List<Keyframe> track = tracks.computeIfAbsent(bone.toLowerCase(), k -> new ArrayList<>());
        track.add(kf);
        track.sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    /**
     * Port of Blockbench BoneAnimator.interpolate: "before" is the last key strictly before the
     * playhead, "after" the first key at or after it. Outside the keyed range the nearest key holds.
     */
    private static Vector3f evaluate(List<Keyframe> track, float time, Vector3f fallback, Vector3f out) {
        if (track == null || track.isEmpty()) {
            return out.set(fallback);
        }
        int n = track.size();
        int a = 0;
        while (a < n && track.get(a).time < time) a++;
        Keyframe after = a < n ? track.get(a) : null;
        Keyframe before = a > 0 ? track.get(a - 1) : null;

        if (before != null && Math.abs(before.time - time) < EPSILON) return out.set(before.post);
        if (after != null && Math.abs(after.time - time) < EPSILON) return out.set(after.pre);
        if (before == null) return out.set(after.pre);
        if (after == null) return out.set(before.post);
        if (before.interpolation == Interpolation.STEP) return out.set(before.post);

        float dt = after.time - before.time;
        float alpha = dt <= 0f ? 1f : (time - before.time) / dt;

        if (before.interpolation == Interpolation.CATMULLROM || after.interpolation == Interpolation.CATMULLROM) {
            // Blockbench builds a THREE.SplineCurve from the neighbours; a missing neighbour is the
            // end key itself, which is what SplineCurve's index clamping does.
            Vector3f p0 = a - 2 >= 0 ? track.get(a - 2).post : before.post;
            Vector3f p1 = before.post;
            Vector3f p2 = after.pre;
            Vector3f p3 = a + 1 < n ? track.get(a + 1).pre : after.pre;
            return out.set(
                    catmullRom(alpha, p0.x, p1.x, p2.x, p3.x),
                    catmullRom(alpha, p0.y, p1.y, p2.y, p3.y),
                    catmullRom(alpha, p0.z, p1.z, p2.z, p3.z));
        }
        return out.set(before.post).lerp(after.pre, alpha);
    }

    /** Uniform Catmull-Rom, identical to three.js Interpolations.CatmullRom. */
    private static float catmullRom(float t, float p0, float p1, float p2, float p3) {
        float v0 = (p2 - p0) * 0.5f;
        float v1 = (p3 - p1) * 0.5f;
        float t2 = t * t;
        float t3 = t * t2;
        return (2f * p1 - 2f * p2 + v0 + v1) * t3 + (-3f * p1 + 3f * p2 - 2f * v0 - v1) * t2 + v0 * t + p1;
    }
    // #endregion
}
