package com.exea.entity.core.skill;

import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Random;

/** Everything an action needs when it runs: who casts, which skill, the target, and where the action is. */
public final class ActionContext {

    public final SkillController caster;
    public final Moveset.Skill skill;
    /** The cast this action belongs to; null inside a projectile's on_hit after the cast ended. */
    public final SkillCast cast;
    public final LivingEntity target;
    public final Moveset.ActionSpec spec;
    /** Set for actions inside a projectile's on_hit: the impact point. */
    public final Location impact;
    public final Random random;

    ActionContext(SkillController caster, Moveset.Skill skill, SkillCast cast, LivingEntity target, Moveset.ActionSpec spec,
                  Location impact, Random random) {
        this.caster = caster;
        this.skill = skill;
        this.cast = cast;
        this.target = target;
        this.spec = spec;
        this.impact = impact;
        this.random = random;
    }

    public EXActiveEntity active() {
        return caster.active;
    }

    public LivingEntity driver() {
        return caster.active.getRootEntity();
    }

    /** Facing used for shapes and directions: the driver's body yaw plus the anchor's yaw. */
    public float yaw() {
        return driver().getBodyYaw() + spec.anchor.yaw();
    }

    /**
     * Where the action happens: the impact point (on_hit), else the bone pivot, else the target (anchor
     * "at": "target"), else the driver's feet; then the anchor offset and ground snap.
     */
    public Frame frame() {
        Location base;
        if (impact != null) base = impact.clone();
        else if (spec.bone != null) {
            base = active().getBoneLocation(spec.bone);
            if (base == null) base = driver().getLocation().add(0, 1, 0);
        } else if (spec.anchor.atTarget() && target != null && target.isValid()) base = target.getLocation();
        else base = driver().getLocation();
        float yaw = yaw();
        Frame f = new Frame(base, yaw);
        double[] o = spec.anchor.offset();
        Location p = (o[0] != 0 || o[1] != 0 || o[2] != 0) ? f.toWorld(o[0], o[1], o[2]) : base;
        if (spec.anchor.ground()) {
            Double gy = Frame.groundY(p.getWorld(), p.getX(), p.getY(), p.getZ(), 8);
            if (gy != null) p.setY(gy);
        }
        return new Frame(p, yaw);
    }

    /** Can a skill of {@code caster} hurt {@code e}? Not itself, other EXea entities, armor stands, creative/spectator players. */
    static boolean canHit(LivingEntity caster, Entity e) {
        if (!(e instanceof LivingEntity t) || t == caster || t.isDead() || !t.isValid() || t instanceof ArmorStand) return false;
        if (EntityManager.getInstance().isActiveEntity(t)) return false;
        return !(t instanceof Player p) || (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR);
    }

    /** Damages through the normal damage events, with the skill id (EXeaEntityDamageEvent#getSkillId). */
    void damage(LivingEntity victim, double amount, String tag) {
        if (amount <= 0) return;
        victim.setNoDamageTicks(0); // multi-hit skills: each hit counts
        String skillId = tag == null ? skill.id : skill.id + ":" + tag;
        EntityManager.getInstance().runAsSkill(skillId, () -> victim.damage(amount, driver()));
    }
}
