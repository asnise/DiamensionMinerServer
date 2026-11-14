package com.exea.entity.api;

import com.exea.entity.api.behavior.EXeaBehavior;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One live EXea entity: an invisible driver mob (hitbox, health, AI, movement) carrying a model drawn
 * with ItemDisplay bones. Get one from {@link EXeaAPI#spawn} or {@link EXeaAPI#get}. All methods must
 * be called on the main thread.
 */
public interface EXeaEntity {

    /** How an animation repeats. */
    enum Loop {
        /** Plays once, then the behavior's {@link EXeaBehavior#onAnimationEnd} decides what comes next. */
        ONCE,
        /** Repeats until another animation is played. */
        LOOP,
        /** Plays once and stays on the last frame. */
        HOLD
    }

    /** Model id, e.g. {@code void_overseer}. */
    String getModelId();

    /** The driver entity (its UUID is this entity's id). */
    LivingEntity getDriver();

    default UUID getUniqueId() {
        return getDriver().getUniqueId();
    }

    /** False once the entity was removed (after {@code EXeaEntityRemoveEvent}). */
    boolean isValid();

    /** True from the start of the death sequence until removal. */
    boolean isDying();

    /** Animation names of the model (lower case). */
    Set<String> getAnimations();

    /** The animation playing (or held on its last frame), or null. */
    String getCurrentAnimation();

    /** Plays an animation from its start with its own loop mode at normal speed. False if the model has none by that name. */
    boolean playAnimation(String name);

    /**
     * Plays an animation from its start.
     *
     * @param speed 1 = normal, 2 = twice as fast
     * @param loop  loop mode for this play, or null for the one set in Blockbench
     */
    boolean playAnimation(String name, float speed, Loop loop);

    /** Stops the animation; the model goes back to its rest pose. */
    void stopAnimation();

    /** Bone names of the model (lower case), including bones without a mesh. */
    Set<String> getBones();

    /**
     * Where a bone's pivot is in the world right now (follows the driver's position, body yaw and the
     * current animation frame). Use it to spawn projectiles or particles from a hand, mouth, weapon tip.
     * Null if the model has no bone by that name.
     */
    Location getBoneLocation(String bone);

    /**
     * Plays an effect preset on a bone (its pivot, following the animation), or on the driver when
     * {@code bone} is null. Null for an unknown effect id.
     */
    default com.exea.entity.api.effect.EffectHandle playEffect(String effectId, String bone) {
        return EXeaAPI.getInstance().playEffect(effectId, this, bone, null);
    }

    /** Same as {@link #playEffect(String, String)} with options (scale, target, yaw, tags). */
    default com.exea.entity.api.effect.EffectHandle playEffect(String effectId, String bone,
                                                               com.exea.entity.api.effect.EffectOptions options) {
        return EXeaAPI.getInstance().playEffect(effectId, this, bone, options);
    }

    // ---- Moveset skills (docs/FORMATS.md §2). Models without a <id>_moveset.json have none.

    /** True if the model has a moveset. */
    default boolean hasMoveset() {
        return false;
    }

    /** Skill ids of the moveset (internal ones included), sorted. */
    default Set<String> getSkills() {
        return Set.of();
    }

    /**
     * Starts a skill now: plays its animation and runs its actions on time. Fires the cancellable
     * {@code EXeaSkillCastEvent}. Ignores the skill's conditions (your plugin decides) but not its cooldown.
     *
     * @param target the skill's target, or null for the driver's current target
     * @return false if unknown, on cooldown, another skill is running, dying, or the event was cancelled
     */
    default boolean castSkill(String skillId, LivingEntity target) {
        return false;
    }

    default boolean castSkill(String skillId) {
        return castSkill(skillId, null);
    }

    /** Stops the running skill (its projectiles keep flying). */
    default void cancelSkill() {
    }

    /** The skill running now, or null. */
    default String getCurrentSkill() {
        return null;
    }

    /** Seconds until the skill can be cast again (0 = ready). */
    default double getCooldown(String skillId) {
        return 0;
    }

    /** Sets a skill's remaining cooldown in seconds (0 = ready now). */
    default void setCooldown(String skillId, double seconds) {
    }

    /** The current HP phase id, or null when the moveset has no phases. */
    default String getPhase() {
        return null;
    }

    /** Whether EXea's AI picks skills by itself. Off = only {@link #castSkill} runs them. */
    default boolean isAutoSkills() {
        return false;
    }

    default void setAutoSkills(boolean auto) {
    }

    /** HP removed per attacker so far (copy). */
    Map<UUID, Double> getDamageByAttacker();

    /** The behavior controlling this entity. */
    EXeaBehavior getBehavior();

    /** Kills it the normal way: death event, death animation, removal. False if already dying or removed. */
    boolean kill();

    /** Removes it at once, without a death (remove event with reason REMOVED). */
    void remove();
}
