package com.exea.entity.core.skill;

import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One running skill: its actions scheduled in ticks (at ÷ speed, repeats expanded), run as the clock passes them. */
public final class SkillCast {

    private record Scheduled(int tick, Moveset.ActionSpec spec, Moveset.Skill owner) { }

    final SkillController ctl;
    final Moveset.Skill skill;
    final LivingEntity target;
    final boolean automatic;
    final double speed;
    private final List<Scheduled> queue = new ArrayList<>();
    private final Map<Moveset.ActionSpec, Set<UUID>> hitOnce = new HashMap<>();
    private int tick;
    private int durationTicks;

    SkillCast(SkillController ctl, Moveset.Skill skill, LivingEntity target, boolean automatic, double speed, float animationLength) {
        this.ctl = ctl;
        this.skill = skill;
        this.target = target;
        this.automatic = automatic;
        this.speed = speed;
        int last = schedule(skill, 0, 0);
        if (skill.duration != null) durationTicks = ticks(skill.duration);
        else durationTicks = Math.max(ticks(animationLength), last + 1);
    }

    private int ticks(double seconds) {
        return (int) Math.round(seconds * 20 / speed);
    }

    /** Adds {@code s}'s actions with {@code at >= from}, shifted to start at tick {@code base}. Returns the last tick. */
    private int schedule(Moveset.Skill s, double from, int base) {
        int last = base;
        for (Moveset.ActionSpec a : s.actions) {
            if (a.at + 1e-9 < from) continue;
            for (int i = 0; i < a.repeatCount; i++) {
                int t = base + ticks(a.at - from + i * a.repeatInterval);
                queue.add(new Scheduled(t, a, s));
                last = Math.max(last, t);
            }
        }
        queue.sort((x, y) -> Integer.compare(x.tick, y.tick));
        return last;
    }

    /** preset_skill: another skill's actions from {@code from} on, starting now; the cast lasts long enough to run them. */
    void insert(Moveset.Skill other, double from) {
        int last = schedule(other, from, tick + 1);
        durationTicks = Math.max(durationTicks, last + 1);
    }

    /** One tick. False when the skill is over. */
    boolean tick() {
        while (!queue.isEmpty() && queue.get(0).tick <= tick) {
            Scheduled s = queue.remove(0);
            ctl.runAction(s.spec, new ActionContext(ctl, s.owner, this, liveTarget(), s.spec, null, ctl.random));
            if (ctl.current != this) return false; // the action ended or replaced the cast
        }
        tick++;
        return tick < durationTicks || !queue.isEmpty() && queue.get(0).tick < durationTicks;
    }

    LivingEntity liveTarget() {
        return target != null && target.isValid() && !target.isDead() ? target : null;
    }

    /** "once" hits: true the first time {@code e} is hit by this action in this cast. */
    boolean markHit(Moveset.ActionSpec spec, LivingEntity e) {
        return hitOnce.computeIfAbsent(spec, k -> new HashSet<>()).add(e.getUniqueId());
    }

    public Moveset.Skill skill() {
        return skill;
    }
}
