package com.exea.entity.core.animation;

import com.exea.entity.api.event.EXeaAnimationEventTriggerEvent;
import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import com.exea.entity.core.timeline.TimelineEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
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
    /** Loop mode for the current play, or null for the timeline's own. */
    private KeyframeTimeline.LoopMode loopOverride;
    /** Each bone's last pose in model space (blocks, root half turn included), by bone name. */
    private final java.util.Map<String, Matrix4f> boneWorld = new java.util.HashMap<>();
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

    /**
     * The bone's pose from the last update, relative to the driver's feet in world axes (body yaw
     * included). Null for an unknown bone. Do not modify.
     */
    public Matrix4f getBoneWorldMatrix(String bone) {
        return boneWorld.get(bone);
    }
    // #endregion

    // #region [Public Methods]
    public AnimationController(LivingEntity rootEntity, ModelStructure modelStructure) {
        this.rootEntity = rootEntity;
        this.modelStructure = modelStructure;
    }

    public void play(KeyframeTimeline timeline) {
        play(timeline, 1.0f, null);
    }

    /** Plays from the start at {@code speed}, with {@code loop} (null = the timeline's own mode). */
    public void play(KeyframeTimeline timeline, float speed, KeyframeTimeline.LoopMode loop) {
        this.speed = speed;
        this.loopOverride = loop;
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
            switch (loopOverride != null ? loopOverride : activeTimeline.getLoopMode()) {
                case LOOP -> {
                    // Keep the overshoot, like Blockbench's time % length, so loops don't drift.
                    float len = activeTimeline.getLength();
                    currentTime = len > 0f ? currentTime % len : 0f;
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

    /**
     * Runs one animation event at its bone (or 1 block above the driver): plays the sound / particle,
     * hands a HIT to the hit handler, then fires {@link EXeaAnimationEventTriggerEvent} for every event.
     */
    private void dispatchTimelineEvent(TimelineEvent event) {
        String animName = activeTimeline.getName();
        Location loc = event.getBone() != null ? boneLocation(event.getBone()) : null;
        if (loc == null) {
            loc = rootEntity.getLocation().add(0, 1.0, 0);
        }

        switch (event.getType()) {
            case SOUND -> loc.getWorld().playSound(loc, event.getSoundKey(), event.getSoundVolume(), event.getSoundPitch());
            case PARTICLE -> {
                try {
                    loc.getWorld().spawnParticle(event.getParticle(), loc, event.getParticleCount(),
                            event.getParticleSpread(), event.getParticleSpread(), event.getParticleSpread(),
                            event.getParticleSpeed());
                } catch (IllegalArgumentException ignored) {
                    // Particles that need extra data (dust colour, block...) cannot come from a keyframe.
                }
            }
            case HIT -> {
                if (hitHandler != null) hitHandler.hit(animName, event, loc);
            }
            case FX -> playEffect(event);
            case CUSTOM -> { }
        }

        Bukkit.getPluginManager().callEvent(new EXeaAnimationEventTriggerEvent(rootEntity, animName, event.getEventTag(), loc));
    }

    /** {@code fx <id> [bone=] [scale=] [follow=] [yaw=]}: an effect preset on the bone (or the driver). */
    private void playEffect(TimelineEvent event) {
        var active = com.exea.entity.core.manager.EntityManager.getInstance().getActiveEntity(rootEntity);
        var fx = com.exea.entity.core.effect.EffectManager.get();
        var opts = com.exea.entity.api.effect.EffectOptions.create().scale(event.getFxScale()).follow(event.isFxFollow());
        if (event.getFxYaw() != 0f) opts.yaw(rootEntity.getBodyYaw() + event.getFxYaw());
        if (rootEntity instanceof org.bukkit.entity.Mob mob && mob.getTarget() != null) opts.target(mob.getTarget());
        if (active != null && event.getBone() != null) fx.play(event.getEffectId(), active, event.getBone(), opts);
        else fx.play(event.getEffectId(), rootEntity, opts);
    }

    /** Receives HIT events (damage around a point); set by the entity. */
    public interface HitHandler {
        void hit(String animation, TimelineEvent event, Location center);
    }

    private HitHandler hitHandler;

    public void setHitHandler(HitHandler hitHandler) {
        this.hitHandler = hitHandler;
    }

    /**
     * Where a bone's pivot is in the world, from the last pose (driver position, body yaw, current
     * frame). Null for an unknown bone.
     */
    public Location boneLocation(String bone) {
        if (poseStale || boneWorld.isEmpty()) {
            // Nobody is near, so poses were skipped: work this one out now, without sending anything.
            computePose(false);
        }
        Matrix4f m = boneWorld.get(bone.toLowerCase());
        if (m == null) return null;
        // The matrix already holds the body yaw (see updateBoneTransforms), so the pivot is an offset
        // from the driver's feet in world axes.
        Vector3f p = m.transformPosition(new Vector3f());
        Location base = rootEntity.getLocation();
        base.add(p.x, p.y, p.z);
        base.setYaw(rootEntity.getBodyYaw());
        base.setPitch(0f);
        return base;
    }

    /** Last matrix sent per display entity; unchanged transforms are not re-sent. */
    private final java.util.Map<java.util.UUID, Matrix4f> lastSent = new java.util.HashMap<>();

    /** Half a turn around Y. See {@link #updateBoneTransforms()}. */
    private static final Matrix4f FLIP_Y = new Matrix4f().rotationY((float) Math.PI);

    // Scratch objects, reused every bone every tick (all on the main thread).
    private final Vector3f tmpPos = new Vector3f();
    private final Vector3f tmpRot = new Vector3f();
    private final Vector3f tmpScale = new Vector3f();
    private final Quaternionf tmpQuat = new Quaternionf();
    private final Matrix4f tmpRoot = new Matrix4f();
    private final Matrix4f tmpSend = new Matrix4f();

    /** False while no player is near: poses are not computed or sent (see {@link #setVisible}). */
    private boolean visible = true;
    /** The stored bone poses are older than the animation clock (skipped while not visible). */
    private boolean poseStale = false;
    /** How far above the carrier the bone displays sit as passengers; taken off every matrix. */
    private float riderOffset = 0f;

    /**
     * Turns pose updates on or off. Off = only the animation clock and its events run; the bones keep
     * their last pose (nobody is near to see it). Turning it back on sends the current pose.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setRiderOffset(float riderOffset) {
        this.riderOffset = riderOffset;
    }

    /** Forgets what was sent, so the next update sends every bone (after bones were respawned). */
    public void resendAll() {
        lastSent.clear();
    }

    /**
     * Poses every bone the way Blockbench's three.js scene does, then hands each display entity its
     * matrix. The displays ride a carrier at the driver's feet with yaw 0, so the root matrix carries
     * the body yaw, and two half turns around Y bracket the Blockbench pose:
     * <ul>
     *   <li>Root: Ry(-bodyYaw) like a display entity turned to the mob's yaw (the client renders a
     *       display with yaw y as Ry(-y)), then a half turn: Blockbench models face north (-Z) while a
     *       mob with yaw 0 faces south (+Z).</li>
     *   <li>Per display: the client's ItemDisplay renderer itself rotates every item 180 degrees
     *       around Y before drawing it. Appending the same half turn cancels that, so the mesh
     *       (exported relative to its pivot) is drawn exactly as in Blockbench.</li>
     * </ul>
     * Turning the body therefore changes the bone matrices instead of teleporting every bone.
     */
    private void updateBoneTransforms() {
        if (!visible) {
            poseStale = true;
            return;
        }
        computePose(true);
    }

    private void computePose(boolean send) {
        // Respawned bones get new entity ids; drop stale entries instead of growing forever.
        if (lastSent.size() > 256) lastSent.clear();
        tmpRoot.rotationY((float) Math.toRadians(-rootEntity.getBodyYaw())).mul(FLIP_Y);
        for (BoneNode rootBone : modelStructure.getRootBones()) {
            updateBoneRecursive(rootBone, tmpRoot, send);
        }
        poseStale = false;
        com.exea.entity.core.EXeaStats.poses++;
    }

    /**
     * Blockbench bone matrix: T(origin - parentOrigin + animPos) * R(rest + animRot, order ZYX) * S(animScale).
     * Animated rotation is added to the rest rotation as Euler angles (BoneAnimator.displayRotation does
     * {@code mesh.rotation[axis] += value}), and three.js builds the matrix with order 'ZYX' = Rz*Ry*Rx.
     */
    private void updateBoneRecursive(BoneNode bone, Matrix4f parentMatrix, boolean send) {
        // One matrix per bone, reused every tick (children read their parent's while it is unchanged).
        Matrix4f world = boneWorld.computeIfAbsent(bone.getName(), k -> new Matrix4f()).set(parentMatrix);

        Vector3f pivot = bone.getPivot();
        if (bone.getParent() != null) {
            Vector3f pp = bone.getParent().getPivot();
            tmpPos.set(pivot.x - pp.x, pivot.y - pp.y, pivot.z - pp.z).mul(1f / 16f);
        } else {
            tmpPos.set(pivot).mul(1f / 16f);
        }
        world.translate(tmpPos);

        String name = bone.getName();
        if (activeTimeline != null) {
            world.translate(activeTimeline.evaluatePosition(name, currentTime, tmpPos));
            activeTimeline.evaluateRotation(name, currentTime, tmpRot);
            activeTimeline.evaluateScale(name, currentTime, tmpScale);
        } else {
            tmpRot.zero();
            tmpScale.set(1f, 1f, 1f);
        }
        tmpRot.add(bone.getDefaultRotation());
        if (tmpRot.lengthSquared() > 1e-8f) {
            world.rotate(tmpQuat.rotationZYX(
                    (float) Math.toRadians(tmpRot.z),
                    (float) Math.toRadians(tmpRot.y),
                    (float) Math.toRadians(tmpRot.x)));
        }
        world.scale(tmpScale);

        ItemDisplay display = bone.getDisplayEntity();
        if (send && bone.hasMesh() && display != null && display.isValid()) {
            // The display rides the carrier riderOffset blocks up: move the pose back down by that.
            Matrix4f m = tmpSend.translation(0f, -riderOffset, 0f).mul(world).mul(FLIP_Y).scale(bone.getMeshScale());
            Matrix4f prev = lastSent.get(display.getUniqueId());
            // Each change is a metadata packet to every viewer: skip bones that did not move
            // (idle statues, held poses, static parts of a model).
            if (prev == null || !prev.equals(m, 1e-5f)) {
                display.setInterpolationDelay(0);
                // First pose after a (re)spawn snaps; later ones blend over the 1-tick update period.
                display.setInterpolationDuration(prev == null ? 0 : 1);
                // Any affine matrix (including shear from non-uniform parent scale) maps exactly onto
                // the display's left-rotation/scale/right-rotation form.
                display.setTransformationMatrix(m);
                if (prev == null) lastSent.put(display.getUniqueId(), new Matrix4f(m));
                else prev.set(m);
                com.exea.entity.core.EXeaStats.boneUpdates++;
            }
        }

        for (BoneNode child : bone.getChildren()) {
            updateBoneRecursive(child, world, send);
        }
    }
    // #endregion
}
