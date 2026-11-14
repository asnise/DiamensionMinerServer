package com.exea.entity.api.event;

import com.exea.entity.api.EXeaEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** An EXea entity with a moveset entered a new HP phase. Phases only go forward. */
public class EXeaPhaseChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final EXeaEntity entity;
    private final String from;
    private final String to;

    public EXeaPhaseChangeEvent(EXeaEntity entity, String from, String to) {
        this.entity = entity;
        this.from = from;
        this.to = to;
    }

    public EXeaEntity getEntity() { return entity; }
    /** The previous phase id, or null for the first. */
    public String getFrom() { return from; }
    public String getTo() { return to; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}