package com.exea.entity.api.event;

import com.exea.entity.api.EXeaEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A moveset skill ended. Projectiles it fired may still be flying. */
public class EXeaSkillEndEvent extends Event {

    public enum Reason {
        /** It ran to its end. */
        FINISHED,
        /** cancelSkill(), a phase on_enter, or another plugin. */
        CANCELLED,
        /** Hit hard enough while interruptible. */
        INTERRUPTED,
        /** The entity died or was removed. */
        DIED
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final EXeaEntity entity;
    private final String skillId;
    private final Reason reason;

    public EXeaSkillEndEvent(EXeaEntity entity, String skillId, Reason reason) {
        this.entity = entity;
        this.skillId = skillId;
        this.reason = reason;
    }

    public EXeaEntity getEntity() { return entity; }
    public String getSkillId() { return skillId; }
    public Reason getReason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}