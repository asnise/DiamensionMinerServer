package com.exea.entity.core.animation;

import com.exea.entity.core.timeline.TimelineEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyframeTimeline {

    public enum LoopMode {
        LOOP,
        ONCE,
        HOLD
    }

    public static class VectorKeyframe {
        private final float time;
        private final Vector3f value;

        public VectorKeyframe(float time, Vector3f value) {
            this.time = time;
            this.value = value;
        }

        public float getTime() {
            return time;
        }

        public Vector3f getValue() {
            return value;
        }
    }

    public static class RotationKeyframe {
        private final float time;
        private final Quaternionf value;

        public RotationKeyframe(float time, Quaternionf value) {
            this.time = time;
            this.value = value;
        }

        public float getTime() {
            return time;
        }

        public Quaternionf getValue() {
            return value;
        }
    }

    // #region [Variables/Fields]
    private final String name;
    private final float length;
    private final LoopMode loopMode;
    private final Map<String, List<VectorKeyframe>> positionTracks = new HashMap<>();
    private final Map<String, List<RotationKeyframe>> rotationTracks = new HashMap<>();
    private final Map<String, List<VectorKeyframe>> scaleTracks = new HashMap<>();
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

    public void addPositionKeyframe(String bone, float time, Vector3f pos) {
        List<VectorKeyframe> track = positionTracks.computeIfAbsent(bone.toLowerCase(), k -> new ArrayList<>());
        track.add(new VectorKeyframe(time, pos));
        track.sort(java.util.Comparator.comparingDouble(VectorKeyframe::getTime));
    }

    public void addRotationKeyframe(String bone, float time, Quaternionf rot) {
        List<RotationKeyframe> track = rotationTracks.computeIfAbsent(bone.toLowerCase(), k -> new ArrayList<>());
        track.add(new RotationKeyframe(time, rot));
        track.sort(java.util.Comparator.comparingDouble(RotationKeyframe::getTime));
    }

    public void addScaleKeyframe(String bone, float time, Vector3f scale) {
        List<VectorKeyframe> track = scaleTracks.computeIfAbsent(bone.toLowerCase(), k -> new ArrayList<>());
        track.add(new VectorKeyframe(time, scale));
        track.sort(java.util.Comparator.comparingDouble(VectorKeyframe::getTime));
    }

    public void addEvent(TimelineEvent event) {
        events.add(event);
    }

    public Vector3f evaluatePosition(String bone, float time) {
        List<VectorKeyframe> track = positionTracks.get(bone.toLowerCase());
        if (track == null || track.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }
        if (track.size() == 1 || time <= track.get(0).getTime()) {
            return new Vector3f(track.get(0).getValue());
        }
        if (time >= track.get(track.size() - 1).getTime()) {
            return new Vector3f(track.get(track.size() - 1).getValue());
        }

        for (int i = 0; i < track.size() - 1; i++) {
            VectorKeyframe cur = track.get(i);
            VectorKeyframe next = track.get(i + 1);
            if (time >= cur.getTime() && time <= next.getTime()) {
                float dt = next.getTime() - cur.getTime();
                if (dt <= 0.0001f) {
                    return new Vector3f(next.getValue());
                }
                float progress = (time - cur.getTime()) / dt;
                return new Vector3f(cur.getValue()).lerp(next.getValue(), progress);
            }
        }
        return new Vector3f(track.get(0).getValue());
    }

    public Quaternionf evaluateRotation(String bone, float time) {
        List<RotationKeyframe> track = rotationTracks.get(bone.toLowerCase());
        if (track == null || track.isEmpty()) {
            return new Quaternionf();
        }
        if (track.size() == 1 || time <= track.get(0).getTime()) {
            return new Quaternionf(track.get(0).getValue());
        }
        if (time >= track.get(track.size() - 1).getTime()) {
            return new Quaternionf(track.get(track.size() - 1).getValue());
        }

        for (int i = 0; i < track.size() - 1; i++) {
            RotationKeyframe cur = track.get(i);
            RotationKeyframe next = track.get(i + 1);
            if (time >= cur.getTime() && time <= next.getTime()) {
                float dt = next.getTime() - cur.getTime();
                if (dt <= 0.0001f) {
                    return new Quaternionf(next.getValue());
                }
                float progress = (time - cur.getTime()) / dt;
                return new Quaternionf(cur.getValue()).slerp(next.getValue(), progress);
            }
        }
        return new Quaternionf(track.get(0).getValue());
    }

    public Vector3f evaluateScale(String bone, float time) {
        List<VectorKeyframe> track = scaleTracks.get(bone.toLowerCase());
        if (track == null || track.isEmpty()) {
            return new Vector3f(1, 1, 1);
        }
        if (track.size() == 1 || time <= track.get(0).getTime()) {
            return new Vector3f(track.get(0).getValue());
        }
        if (time >= track.get(track.size() - 1).getTime()) {
            return new Vector3f(track.get(track.size() - 1).getValue());
        }

        for (int i = 0; i < track.size() - 1; i++) {
            VectorKeyframe cur = track.get(i);
            VectorKeyframe next = track.get(i + 1);
            if (time >= cur.getTime() && time <= next.getTime()) {
                float dt = next.getTime() - cur.getTime();
                if (dt <= 0.0001f) {
                    return new Vector3f(next.getValue());
                }
                float progress = (time - cur.getTime()) / dt;
                return new Vector3f(cur.getValue()).lerp(next.getValue(), progress);
            }
        }
        return new Vector3f(1, 1, 1);
    }
    // #endregion
}
