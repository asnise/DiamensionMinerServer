package com.exea.entity.api.behavior;

import com.exea.entity.api.EXeaEntity;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.skill.Moveset;
import com.exea.entity.core.skill.SkillController;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The AI for models with a moveset ({@code <id>_moveset.json}), used automatically when nothing else is
 * registered for the model:
 * <ul>
 *   <li>every {@code think_interval}, when no skill runs and the global cooldown is over, it picks a
 *       target and one ready skill whose conditions pass, and casts it;</li>
 *   <li>between skills it plays the idle / move animations, keeps {@code keep_distance} from the target
 *       and drops targets beyond {@code chase_range};</li>
 *   <li>with {@code "melee": true} the driver's own melee hits still land and play a random
 *       {@code attack_*} animation.</li>
 * </ul>
 * Skills are cast only while auto skills are on ({@code ai.mode}, {@link EXeaEntity#setAutoSkills}).
 * Extend it and override {@link #pickSkill} to choose differently.
 */
public class MovesetBehavior implements EXeaBehavior {

    private static final double MOVE_EPSILON_SQ = 0.0015;

    private Location lastLocation;
    private boolean moving;
    private boolean wasCasting;
    private boolean meleeAnimation;
    private int thinkIn;

    @Override
    public void onTick(EXeaEntity entity) {
        SkillController c = controller(entity);
        Location now = entity.getDriver().getLocation();
        boolean movingNow = lastLocation != null && now.getWorld() == lastLocation.getWorld()
                && now.distanceSquared(lastLocation) > MOVE_EPSILON_SQ;
        lastLocation = now;
        boolean casting = c != null && c.isCasting();
        boolean changed = movingNow != moving;
        moving = movingNow;
        if (casting) {
            wasCasting = true;
            return;
        }
        if ((wasCasting || changed) && !meleeAnimation) idleOrMove(entity, c);
        wasCasting = false;
        if (c == null || !c.isAuto() || !c.globalReady()) return;
        if (--thinkIn > 0) return;
        thinkIn = Math.max(1, (int) Math.round(c.moveset.ai.thinkInterval() * 20));
        think(entity, c);
    }

    private void think(EXeaEntity entity, SkillController c) {
        LivingEntity target = c.defaultTarget();
        LivingEntity d = entity.getDriver();
        if (d instanceof Mob mob && mob.getTarget() != null && mob.getTarget() != target && target == null) mob.setTarget(null);
        if (target == null) return;
        keepDistance(entity, c, target);
        List<String> ready = new ArrayList<>();
        for (Moveset.Skill s : c.phaseSkills()) {
            if (c.cooldownLeft(s.id) <= 0 && c.conditionsPass(s, target)) ready.add(s.id);
        }
        if (ready.isEmpty()) return;
        String pick = pickSkill(entity, ready, target);
        if (pick != null) c.cast(pick, target, true, false);
    }

    /**
     * Which of the ready skills to cast at {@code target} (all passed their conditions and cooldowns).
     * Default: among those with the highest {@code priority}, a random one weighted by {@code weight}.
     *
     * @return a skill id from {@code ready}, or null to cast nothing now
     */
    protected String pickSkill(EXeaEntity entity, List<String> ready, LivingEntity target) {
        SkillController c = controller(entity);
        if (c == null) return null;
        int best = Integer.MIN_VALUE;
        for (String id : ready) best = Math.max(best, c.moveset.skills.get(id).priority);
        double total = 0;
        List<Moveset.Skill> top = new ArrayList<>();
        for (String id : ready) {
            Moveset.Skill s = c.moveset.skills.get(id);
            if (s.priority == best && s.weight > 0) {
                top.add(s);
                total += s.weight;
            }
        }
        if (top.isEmpty()) return null;
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (Moveset.Skill s : top) {
            roll -= s.weight;
            if (roll <= 0) return s.id;
        }
        return top.get(top.size() - 1).id;
    }

    private void keepDistance(EXeaEntity entity, SkillController c, LivingEntity target) {
        double keep = c.moveset.ai.keepDistance();
        if (keep <= 0 || !(entity.getDriver() instanceof Mob mob)) return;
        Location me = mob.getLocation();
        Vector away = me.toVector().subtract(target.getLocation().toVector()).setY(0);
        double dist = away.length();
        if (dist >= keep || dist < 1e-3) return;
        Location dest = me.clone().add(away.normalize().multiply(keep - dist + 1.5));
        mob.getPathfinder().moveTo(dest);
    }

    @Override
    public void onAttack(EXeaEntity entity, LivingEntity target) {
        SkillController c = controller(entity);
        if (c != null && (c.isCasting() || !c.moveset.ai.melee()) || meleeAnimation) return;
        List<String> attacks = new ArrayList<>();
        for (String name : entity.getAnimations()) if (name.startsWith("attack_")) attacks.add(name);
        if (attacks.isEmpty()) return;
        attacks.sort(null);
        if (entity.playAnimation(attacks.get(ThreadLocalRandom.current().nextInt(attacks.size())), 1f, EXeaEntity.Loop.ONCE)) {
            meleeAnimation = true;
        }
    }

    @Override
    public void onAnimationEnd(EXeaEntity entity, String animation) {
        meleeAnimation = false;
        SkillController c = controller(entity);
        if (c == null || !c.isCasting()) idleOrMove(entity, c);
    }

    /** True while a skill runs. */
    protected boolean isCasting(EXeaEntity entity) {
        SkillController c = controller(entity);
        return c != null && c.isCasting();
    }

    /** True if the driver moved last tick. */
    protected boolean isMoving() {
        return moving;
    }

    private void idleOrMove(EXeaEntity entity, SkillController c) {
        String idle = c != null ? c.moveset.ai.idleAnimation() : "idle";
        String move = c != null ? c.moveset.ai.moveAnimation() : "move";
        String want = moving && entity.getAnimations().contains(move) ? move : idle;
        if (entity.getAnimations().contains(want) && !want.equals(entity.getCurrentAnimation())) entity.playAnimation(want);
    }

    private static SkillController controller(EXeaEntity entity) {
        return entity instanceof EXActiveEntity a ? a.getSkillController() : null;
    }
}
