package com.exea.entity.api.behavior;

import com.exea.entity.api.EXeaEntity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The built-in behavior, used when nothing else is registered for a model:
 * <ul>
 *   <li>{@code idle} while standing, {@code move} while the driver moves (switches on change only, so
 *       an animation played by hand keeps going until the entity starts or stops moving);</li>
 *   <li>when the driver lands a melee hit, one {@code attack_*} animation at random (none while one plays);</li>
 *   <li>after a ONCE animation ends, back to {@code move} or {@code idle}.</li>
 * </ul>
 * Override {@link #pickAttack} to choose attacks differently.
 */
public class DefaultBehavior implements EXeaBehavior {

    /** Squared distance per tick above which the driver counts as moving. */
    protected static final double MOVE_EPSILON_SQ = 0.0015;

    private Location lastLocation;
    private boolean moving;
    private boolean attacking;

    @Override
    public void onTick(EXeaEntity entity) {
        Location now = entity.getDriver().getLocation();
        boolean movingNow = lastLocation != null && now.getWorld() == lastLocation.getWorld()
                && now.distanceSquared(lastLocation) > MOVE_EPSILON_SQ;
        lastLocation = now;
        if (attacking || movingNow == moving) {
            moving = movingNow;
            return;
        }
        moving = movingNow;
        playIfPresent(entity, moving ? "move" : "idle");
    }

    @Override
    public void onAttack(EXeaEntity entity, LivingEntity target) {
        if (attacking) return;
        String attack = pickAttack(entity, target);
        if (attack != null && entity.playAnimation(attack, 1f, EXeaEntity.Loop.ONCE)) {
            attacking = true;
        }
    }

    @Override
    public void onAnimationEnd(EXeaEntity entity, String animation) {
        attacking = false;
        if (moving && entity.getAnimations().contains("move")) {
            entity.playAnimation("move");
        } else if (!"idle".equals(animation)) {
            playIfPresent(entity, "idle");
        }
    }

    /** True while an attack animation started by this behavior is playing. */
    protected boolean isAttacking() {
        return attacking;
    }

    /** True if the driver moved last tick. */
    protected boolean isMoving() {
        return moving;
    }

    /** Which attack to play when the driver hits {@code target}; null for none. Default: a random {@code attack_*}. */
    protected String pickAttack(EXeaEntity entity, LivingEntity target) {
        List<String> attacks = new ArrayList<>();
        for (String name : entity.getAnimations()) {
            if (name.startsWith("attack_")) attacks.add(name);
        }
        if (attacks.isEmpty()) return null;
        Collections.sort(attacks);
        return attacks.get(ThreadLocalRandom.current().nextInt(attacks.size()));
    }

    protected static void playIfPresent(EXeaEntity entity, String animation) {
        if (entity.getAnimations().contains(animation)) {
            entity.playAnimation(animation);
        }
    }
}
