package com.exea.entity.api.behavior;

import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.event.EXeaEntityRemoveEvent;
import org.bukkit.entity.LivingEntity;

/**
 * Decides what an EXea entity does: which animation plays when, what happens on hits and attacks.
 * One instance per entity (made by the factory given to {@link com.exea.entity.api.EXeaAPI#registerBehavior}
 * or set in {@link com.exea.entity.api.SpawnOptions#behavior}), so it may keep per-entity state.
 *
 * <p>The engine always handles rendering, the death sequence (death animation, removal) and the
 * events; a behavior only adds to that. Every method is optional and runs on the main thread. An
 * exception thrown here is logged and does not stop the entity. Extend {@link DefaultBehavior} to keep
 * the built-in idle / move / attack logic and change one part.
 */
public interface EXeaBehavior {

    /** Right after spawning, before {@code EXeaEntitySpawnEvent}. The engine already started {@code idle} if the model has it. */
    default void onSpawn(EXeaEntity entity) {
    }

    /** Every tick while the entity is alive (not while dying), before the animation advances. */
    default void onTick(EXeaEntity entity) {
    }

    /**
     * After a hit that does not kill it.
     *
     * @param attacker who hit it (shooter for projectiles), or null (fire, fall...)
     * @param damage   final damage of the hit
     */
    default void onDamaged(EXeaEntity entity, LivingEntity attacker, double damage) {
    }

    /** When the driver lands a melee hit on {@code target}. */
    default void onAttack(EXeaEntity entity, LivingEntity target) {
    }

    /** When an animation played with loop mode ONCE reaches its end (not {@code death}). */
    default void onAnimationEnd(EXeaEntity entity, String animation) {
    }

    /** At the start of the death sequence, right after {@code EXeaEntityDeathEvent}. */
    default void onDeath(EXeaEntity entity, LivingEntity killer) {
    }

    /** Right before the bones are removed, after {@code EXeaEntityRemoveEvent}. */
    default void onRemove(EXeaEntity entity, EXeaEntityRemoveEvent.Reason reason) {
    }
}
