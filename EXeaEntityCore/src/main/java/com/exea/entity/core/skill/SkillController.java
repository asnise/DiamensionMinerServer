package com.exea.entity.core.skill;

import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.event.EXeaPhaseChangeEvent;
import com.exea.entity.api.event.EXeaSkillCastEvent;
import com.exea.entity.api.event.EXeaSkillEndEvent;
import com.exea.entity.api.skill.SkillActionContext;
import com.exea.entity.api.skill.SkillActionHandler;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.model.EntityMeta;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The skills of one EXea entity: cooldowns, the running cast, HP phases, and the tasks skills leave
 * behind (projectiles, dashes, summons). Ticked by its entity. The AI that picks skills lives in
 * {@link com.exea.entity.api.behavior.MovesetBehavior}; plugins can cast directly through the API.
 */
public final class SkillController {

    private static final NamespacedKey PHASE_SPEED = new NamespacedKey("exea", "phase_speed");

    public final EXActiveEntity active;
    public final Moveset moveset;
    final Random random = new Random();
    SkillCast current;
    private boolean auto;
    private int now;
    private int globalReadyAt;
    private int phase = -1;
    private int invulnerableUntil = -1;
    private final Map<String, Integer> readyAt = new HashMap<>();
    private final List<SkillManager.Task> tasks = new ArrayList<>();
    private final List<EXActiveEntity> boundSummons = new ArrayList<>();
    private final List<EXActiveEntity> summons = new ArrayList<>();
    private final List<Object[]> delayed = new ArrayList<>(); // {Integer tick, Runnable}

    public SkillController(EXActiveEntity active, Moveset moveset, boolean auto) {
        this.active = active;
        this.moveset = moveset;
        this.auto = auto;
    }

    // ------------------------------------------------------------------ state

    public boolean isAuto() { return auto; }
    public void setAuto(boolean auto) { this.auto = auto; }
    public boolean isCasting() { return current != null; }
    public String currentSkill() { return current == null ? null : current.skill.id; }
    public boolean globalReady() { return now >= globalReadyAt; }
    public int now() { return now; }

    public String phaseId() {
        return phase >= 0 && phase < moveset.phases.size() ? moveset.phases.get(phase).id() : null;
    }

    public double phaseSpeed() {
        return phase >= 0 && phase < moveset.phases.size() ? moveset.phases.get(phase).speed() : 1;
    }

    public double cooldownLeft(String id) {
        return Math.max(0, readyAt.getOrDefault(id, 0) - now) / 20.0;
    }

    public void setCooldown(String id, double seconds) {
        readyAt.put(id, now + (int) Math.round(Math.max(0, seconds) * 20));
    }

    /** Skills the AI may pick now: in the current phase's list (all when it has none) and not internal. */
    public List<Moveset.Skill> phaseSkills() {
        List<Moveset.Skill> out = new ArrayList<>();
        List<String> allowed = phase >= 0 && phase < moveset.phases.size() ? moveset.phases.get(phase).skills() : null;
        for (Moveset.Skill s : moveset.skills.values()) {
            if (s.internal) continue;
            if (allowed != null && !allowed.contains(s.id)) continue;
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ ticking

    /** Every tick while alive. */
    public void tick() {
        now++;
        updatePhase();
        tickTasks();
        if (invulnerableUntil >= 0 && now >= invulnerableUntil) {
            invulnerableUntil = -1;
            if (!active.isDying()) active.getRootEntity().setInvulnerable(false);
        }
        SkillCast c = current;
        if (c != null) {
            LivingEntity d = active.getRootEntity();
            if ("track".equals(c.skill.face) && c.liveTarget() != null) face(c.liveTarget());
            if (c.skill.root) {
                if (d instanceof Mob mob) mob.getPathfinder().stopPathfinding();
                Vector v = d.getVelocity();
                d.setVelocity(new Vector(0, Math.min(0, v.getY()), 0));
            }
            boolean go;
            try {
                go = c.tick();
            } catch (RuntimeException e) {
                SkillManager.get().warnOnce("cast:" + c.skill.id, "skill " + c.skill.id + " of " + active.getModelId() + " failed: " + e);
                go = false;
            }
            if (!go && current == c) end(EXeaSkillEndEvent.Reason.FINISHED);
        }
    }

    /** Projectiles, dashes and timers (they also run while the entity dies). */
    public void tickTasks() {
        for (int i = 0; i < tasks.size(); i++) {
            SkillManager.Task t = tasks.get(i);
            boolean keep;
            try {
                keep = t.tick();
            } catch (RuntimeException e) {
                SkillManager.get().warnOnce("task:" + e.getClass(), "skill task failed: " + e);
                keep = false;
            }
            if (!keep) {
                t.stop();
                tasks.remove(i--);
            }
        }
        for (Iterator<Object[]> it = delayed.iterator(); it.hasNext(); ) {
            Object[] d = it.next();
            if ((int) d[0] <= now) {
                it.remove();
                try {
                    ((Runnable) d[1]).run();
                } catch (RuntimeException ignored) {
                    // a timer for an entity that is gone
                }
            }
        }
        summons.removeIf(s -> !s.isValid());
        boundSummons.removeIf(s -> !s.isValid());
    }

    void run(SkillManager.Task t) {
        tasks.add(t);
    }

    void later(int ticks, Runnable r) {
        delayed.add(new Object[]{now + Math.max(1, ticks), r});
    }

    // ------------------------------------------------------------------ casting

    /**
     * Starts a skill. {@code force} skips the cooldown and stops a running skill (phase on_enter);
     * otherwise a running skill or a cooldown refuses it. Conditions are the caller's job.
     */
    public boolean cast(String id, LivingEntity target, boolean automatic, boolean force) {
        Moveset.Skill s = moveset.skills.get(id);
        if (s == null || active.isDying() || !active.getRootEntity().isValid()) return false;
        if (current != null) {
            if (!force) return false;
            end(EXeaSkillEndEvent.Reason.CANCELLED);
        }
        if (!force && cooldownLeft(id) > 0) return false;
        if (target == null) target = defaultTarget();
        EXeaSkillCastEvent ev = new EXeaSkillCastEvent(active, id, target, automatic);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;

        double speed = s.speed * phaseSpeed();
        float animLength = 0;
        if (s.animation != null) {
            if (active.getAnimations().contains(s.animation)) {
                active.playAnimation(s.animation, (float) speed, EXeaEntity.Loop.ONCE);
                animLength = active.getAnimationLength(s.animation);
            } else {
                SkillManager.get().warnOnce("anim:" + active.getModelId() + "/" + s.animation,
                        "skill " + id + ": model " + active.getModelId() + " has no animation \"" + s.animation + "\"");
            }
        }
        if (!"none".equals(s.face) && target != null) face(target);
        readyAt.put(id, now + (int) Math.round(s.cooldown * 20));
        current = new SkillCast(this, s, target, automatic, speed, animLength);
        return true;
    }

    /** Ends the running skill; FINISHED also casts its followup (chance rolled). */
    public void end(EXeaSkillEndEvent.Reason reason) {
        SkillCast c = current;
        if (c == null) return;
        current = null;
        globalReadyAt = now + (int) Math.round(moveset.ai.globalCooldown() * 20);
        Bukkit.getPluginManager().callEvent(new EXeaSkillEndEvent(active, c.skill.id, reason));
        if (reason != EXeaSkillEndEvent.Reason.FINISHED && c.skill.animation != null && c.skill.animation.equals(active.getCurrentAnimation())) {
            active.stopAnimation();
        }
        if (reason == EXeaSkillEndEvent.Reason.FINISHED && c.skill.followup != null && random.nextDouble() < c.skill.followupChance) {
            cast(c.skill.followup, c.liveTarget(), false, true);
        }
    }

    /** A hit taken: interrupts an interruptible skill if it was big enough. */
    public void onDamaged(double damage) {
        if (current != null && current.skill.interruptible && damage > current.skill.interruptDamage) {
            end(EXeaSkillEndEvent.Reason.INTERRUPTED);
        }
    }

    /** Death started: the running skill stops; flying projectiles finish. */
    public void onDeath() {
        end(EXeaSkillEndEvent.Reason.DIED);
    }

    /** Entity removed: everything stops, bound summons are removed. */
    public void shutdown() {
        end(EXeaSkillEndEvent.Reason.DIED);
        for (SkillManager.Task t : tasks) t.stop();
        tasks.clear();
        delayed.clear();
        for (EXActiveEntity s : boundSummons) if (s.isValid()) s.remove();
        boundSummons.clear();
    }

    void runAction(Moveset.ActionSpec spec, ActionContext ctx) {
        try {
            if (spec.action != null) {
                spec.action.run(ctx);
                return;
            }
            SkillActionHandler h = SkillManager.get().customAction(spec.type);
            if (h == null) {
                SkillManager.get().warnOnce("action:" + spec.type, "action type \"" + spec.type + "\" is not built in and no plugin registered it");
                return;
            }
            Location at = ctx.frame().location();
            h.run(new SkillActionContext() {
                public EXeaEntity getCaster() { return active; }
                public String getSkillId() { return ctx.skill.id; }
                public LivingEntity getTarget() { return ctx.target; }
                public Location getLocation() { return at.clone(); }
                public JsonObject getJson() { return spec.json; }
            });
        } catch (RuntimeException e) {
            SkillManager.get().warnOnce("run:" + ctx.skill.id + "/" + spec.type, "skill " + ctx.skill.id + ": " + spec.type + " action failed: " + e);
        }
    }

    // ------------------------------------------------------------------ phases

    private void updatePhase() {
        if (moveset.phases.isEmpty() || active.isDying()) return;
        LivingEntity d = active.getRootEntity();
        AttributeInstance max = d.getAttribute(Attribute.MAX_HEALTH);
        double frac = max == null || max.getValue() <= 0 ? 1 : d.getHealth() / max.getValue();
        int idx = moveset.phaseIndexFor(frac);
        if (idx <= phase) return; // phases only go forward
        String from = phaseId();
        phase = idx;
        Moveset.Phase p = moveset.phases.get(idx);
        AttributeInstance ms = d.getAttribute(Attribute.MOVEMENT_SPEED);
        if (ms != null) {
            ms.removeModifier(PHASE_SPEED);
            if (p.speed() != 1) ms.addTransientModifier(new AttributeModifier(PHASE_SPEED, p.speed() - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
        Bukkit.getPluginManager().callEvent(new EXeaPhaseChangeEvent(active, from, p.id()));
        if (p.invulnerableOnEnter() > 0) makeInvulnerable((int) Math.round(p.invulnerableOnEnter() * 20));
        if (p.onEnter() != null) cast(p.onEnter(), null, false, true);
    }

    void makeInvulnerable(int ticks) {
        active.getRootEntity().setInvulnerable(true);
        invulnerableUntil = Math.max(invulnerableUntil, now + ticks);
    }

    void refreshName() {
        EntityMeta m = active.getMeta();
        if (m.nameVisible()) active.getRootEntity().setCustomName(m.healthName(active.getRootEntity().getHealth()));
    }

    // ------------------------------------------------------------------ summons

    void addSummon(EXActiveEntity s, boolean bound) {
        summons.add(s);
        if (bound) boundSummons.add(s);
    }

    int aliveSummons(String model) {
        int n = 0;
        for (EXActiveEntity s : summons) if (s.isValid() && !s.isDying() && s.getModelId().equals(model)) n++;
        return n;
    }

    // ------------------------------------------------------------------ targets and facing

    /** The driver's current target, or the nearest player in range (which becomes its target). */
    public LivingEntity defaultTarget() {
        return selectTarget(moveset.ai.target());
    }

    public double chaseRange() {
        Double r = moveset.ai.chaseRange();
        return r != null ? r : active.getMeta().followRange();
    }

    /** Picks a target by mode (§2.3 {@code target}): current, nearest, farthest, random, lowest_hp. */
    public LivingEntity selectTarget(String mode) {
        LivingEntity d = active.getRootEntity();
        double range = chaseRange();
        if ("current".equals(mode) || mode == null) {
            if (d instanceof Mob mob && mob.getTarget() != null && valid(mob.getTarget(), range)) return mob.getTarget();
            mode = "nearest";
        }
        List<Player> players = new ArrayList<>();
        for (Player p : d.getWorld().getPlayers()) if (valid(p, range)) players.add(p);
        if (players.isEmpty()) return null;
        Location at = d.getLocation();
        Player pick = switch (mode) {
            case "farthest" -> players.stream().max((a, b) -> Double.compare(a.getLocation().distanceSquared(at), b.getLocation().distanceSquared(at))).get();
            case "random" -> players.get(random.nextInt(players.size()));
            case "lowest_hp" -> players.stream().min((a, b) -> Double.compare(a.getHealth(), b.getHealth())).get();
            default -> players.stream().min((a, b) -> Double.compare(a.getLocation().distanceSquared(at), b.getLocation().distanceSquared(at))).get();
        };
        if (d instanceof Mob mob && mob.getTarget() == null) mob.setTarget(pick);
        return pick;
    }

    private boolean valid(LivingEntity t, double range) {
        LivingEntity d = active.getRootEntity();
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != d.getWorld()) return false;
        if (t instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return false;
        return t.getLocation().distanceSquared(d.getLocation()) <= range * range;
    }

    /** Do the skill's conditions (§2.3) pass against {@code target}? */
    public boolean conditionsPass(Moveset.Skill s, LivingEntity target) {
        Moveset.Conditions c = s.conditions;
        LivingEntity d = active.getRootEntity();
        if (c.range() != null) {
            if (target == null) return false;
            double dist = target.getLocation().distance(d.getLocation());
            if (dist < c.range()[0] || dist > c.range()[1]) return false;
        }
        if (c.hp() != null) {
            AttributeInstance max = d.getAttribute(Attribute.MAX_HEALTH);
            double frac = max == null ? 1 : d.getHealth() / max.getValue();
            if (frac < c.hp()[0] || frac > c.hp()[1]) return false;
        }
        if (c.targetsMin() != null) {
            int n = 0;
            double r2 = c.targetsRadius() * c.targetsRadius();
            for (Player p : d.getWorld().getPlayers()) if (valid(p, 1e9) && p.getLocation().distanceSquared(d.getLocation()) <= r2) n++;
            if (n < c.targetsMin()) return false;
        }
        if (c.lineOfSight() != null && target != null && c.lineOfSight() != d.hasLineOfSight(target)) return false;
        if (c.targetAirborne() != null && target != null && c.targetAirborne() == target.isOnGround()) return false;
        return c.chance() == null || random.nextDouble() < c.chance();
    }

    void face(LivingEntity t) {
        LivingEntity d = active.getRootEntity();
        Vector to = t.getLocation().toVector().subtract(d.getLocation().toVector());
        if (to.getX() * to.getX() + to.getZ() * to.getZ() < 1e-6) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        d.setRotation(yaw, 0);
        d.setBodyYaw(yaw);
    }
}
