package com.exea.entity.core.animation;

import com.exea.entity.api.event.EXeaAnimationEventTriggerEvent;
import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import com.exea.entity.core.timeline.TimelineEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

public class AnimationController {

    // #region [Variables/Fields]
    private final LivingEntity rootEntity;
    private final ModelStructure modelStructure;
    private KeyframeTimeline activeTimeline;
    private float currentTime = 0.0f;
    private float speed = 1.0f;
    private boolean isPlaying = false;
    private final Set<TimelineEvent> triggeredEvents = new HashSet<>();
    private java.util.function.Consumer<String> onFinishCallback;
    // #endregion

    // #region [Properties]
    public boolean isPlaying() {
        return isPlaying;
    }

    public KeyframeTimeline getActiveTimeline() {
        return activeTimeline;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public float getSpeed() {
        return speed;
    }

    public void setOnFinishCallback(java.util.function.Consumer<String> callback) {
        this.onFinishCallback = callback;
    }
    // #endregion

    // #region [Public Methods]
    public AnimationController(LivingEntity rootEntity, ModelStructure modelStructure) {
        this.rootEntity = rootEntity;
        this.modelStructure = modelStructure;
    }

    public void play(KeyframeTimeline timeline) {
        this.activeTimeline = timeline;
        this.currentTime = 0.0f;
        this.isPlaying = true;
        this.triggeredEvents.clear();
        updateBoneTransforms();
    }

    public void stop() {
        this.isPlaying = false;
        this.activeTimeline = null;
        this.currentTime = 0.0f;
        this.triggeredEvents.clear();
        updateBoneTransforms();
    }

    public void tick(float deltaSeconds) {
        if (!isPlaying || activeTimeline == null) {
            updateBoneTransforms();
            return;
        }

        currentTime += deltaSeconds * speed;

        checkTimelineEvents();
        updateBoneTransforms();

        if (currentTime >= activeTimeline.getLength()) {
            switch (activeTimeline.getLoopMode()) {
                case LOOP -> {
                    currentTime = 0.0f;
                    triggeredEvents.clear();
                }
                case HOLD -> isPlaying = false;
                case ONCE -> {
                    String finishedAnim = activeTimeline.getName();
                    stop();
                    if (onFinishCallback != null) {
                        onFinishCallback.accept(finishedAnim);
                    }
                }
            }
        }
    }
    // #endregion

    // #region [Private Methods]
    private void checkTimelineEvents() {
        for (TimelineEvent event : activeTimeline.getEvents()) {
            if (currentTime >= event.getTimestamp() && !triggeredEvents.contains(event)) {
                triggeredEvents.add(event);
                dispatchTimelineEvent(event);
            }
        }
    }

    private void dispatchTimelineEvent(TimelineEvent event) {
        Location loc = rootEntity.getLocation();

        if (event.getSound() != null) {
            loc.getWorld().playSound(loc, event.getSound(), event.getSoundVolume(), event.getSoundPitch());
        }

        if (event.getParticle() != null) {
            loc.getWorld().spawnParticle(
                    event.getParticle(),
                    loc.clone().add(0, 1.0, 0),
                    event.getParticleCount(),
                    event.getParticleSpread(),
                    event.getParticleSpread(),
                    event.getParticleSpread()
            );
        }

        EXeaAnimationEventTriggerEvent bukkitEvent = new EXeaAnimationEventTriggerEvent(
                rootEntity,
                activeTimeline.getName(),
                event.getEventTag(),
                loc
        );
        Bukkit.getPluginManager().callEvent(bukkitEvent);
    }

    /** Last transformation sent per display entity; unchanged transforms are not re-sent. */
    private final java.util.Map<java.util.UUID, Transformation> lastSent = new java.util.HashMap<>();
    /** Default (rest) rotation per bone: constant, so built once instead of every tick. */
    private final java.util.IdentityHashMap<BoneNode, Quaternionf> defaultRotations = new java.util.IdentityHashMap<>();

    private Quaternionf defaultRotation(BoneNode bone) {
        return defaultRotations.computeIfAbsent(bone, b -> new Quaternionf().rotationXYZ(
                (float) Math.toRadians(b.getDefaultRotation().x),
                (float) Math.toRadians(b.getDefaultRotation().y),
                (float) Math.toRadians(b.getDefaultRotation().z)));
    }

    private void updateBoneTransforms() {
        // Respawned bones get new entity ids; drop stale entries instead of growing forever.
        if (lastSent.size() > 256) lastSent.clear();
        Matrix4f rootMatrix = new Matrix4f().identity();
        for (BoneNode rootBone : modelStructure.getRootBones()) {
            updateBoneRecursive(rootBone, rootMatrix);
        }
    }

    private void updateBoneRecursive(BoneNode bone, Matrix4f parentMatrix) {
        Matrix4f currentWorldMatrix = new Matrix4f(parentMatrix);

        if (bone.getParent() != null) {
            Vector3f relPivot = new Vector3f(bone.getPivot())
                    .sub(bone.getParent().getPivot())
                    .mul(1.0f / 16.0f);
            currentWorldMatrix.translate(relPivot);
        } else {
            Vector3f rootPivot = new Vector3f(bone.getPivot()).mul(1.0f / 16.0f);
            currentWorldMatrix.translate(rootPivot);
        }

        Vector3f animPos = activeTimeline != null ? activeTimeline.evaluatePosition(bone.getName(), currentTime) : new Vector3f(0, 0, 0);
        currentWorldMatrix.translate(animPos);

        if (bone.getDefaultRotation().lengthSquared() > 0.0001f) {
            currentWorldMatrix.rotate(defaultRotation(bone));
        }

        Quaternionf animRot = activeTimeline != null ? activeTimeline.evaluateRotation(bone.getName(), currentTime) : new Quaternionf();
        Vector3f animScale = activeTimeline != null ? activeTimeline.evaluateScale(bone.getName(), currentTime) : new Vector3f(1, 1, 1);

        currentWorldMatrix.rotate(animRot);
        currentWorldMatrix.scale(animScale);

        if (bone.hasMesh() && bone.getDisplayEntity() != null && bone.getDisplayEntity().isValid()) {
            Vector3f trans = new Vector3f();
            currentWorldMatrix.getTranslation(trans);

            Quaternionf rot = new Quaternionf();
            currentWorldMatrix.getUnnormalizedRotation(rot);

            Vector3f scl = new Vector3f();
            currentWorldMatrix.getScale(scl);

            Transformation transformation = new Transformation(trans, rot, scl, new Quaternionf());
            ItemDisplay display = bone.getDisplayEntity();
            // Each change is a metadata packet to every viewer: skip bones that did not move
            // (idle statues, held poses, static parts of a model).
            if (!transformation.equals(lastSent.get(display.getUniqueId()))) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(1);
                display.setTransformation(transformation);
                lastSent.put(display.getUniqueId(), transformation);
            }
        }

        for (BoneNode child : bone.getChildren()) {
            updateBoneRecursive(child, currentWorldMatrix);
        }
    }
    // #endregion
}
