package com.exea.entity.api.event;

import com.exea.entity.api.EXeaEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Before an EXea entity starts a moveset skill. Cancel it and the skill does not run (no cooldown is used). */
public class EXeaSkillCastEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final EXeaEntity entity;
    private final String skillId;
    private final LivingEntity target;
    private final boolean automatic;
    private boolean cancelled;

    public EXeaSkillCastEvent(EXeaEntity entity, String skillId, LivingEntity target, boolean automatic) {
        this.entity = entity;
        this.skillId = skillId;
        this.target = target;
        this.automatic = automatic;
    }

    public EXeaEntity getEntity() { return entity; }
    public String getSkillId() { return skillId; }
    /** The skill's target, or null. */
    public LivingEntity getTarget() { return target; }
    /** True when EXea's own AI chose it; false for castSkill, phase on_enter and followups. */
    public boolean isAutomatic() { return automatic; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}