package com.exea.entity.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired exactly once per EXea entity, when its bones are about to be removed and it stops being an
 * EXea entity. The driver may still be valid during the event (for {@link Reason#DIED} it is removed
 * right after), so its location can be read.
 */
public class EXeaEntityRemoveEvent extends Event {

    public enum Reason {
        /** The death sequence finished (an {@link EXeaEntityDeathEvent} was fired before). */
        DIED,
        /** Removed on purpose: {@code /exea kill}, or a plugin through the API. */
        REMOVED,
        /** The driver entity disappeared without a death sequence (removed by another plugin, world unloaded...). */
        DRIVER_LOST,
        /** EXeaEntityCore is being disabled (server stop or reload). */
        PLUGIN_DISABLE
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity entity;
    private final String templateId;
    private final Reason reason;

    public EXeaEntityRemoveEvent(LivingEntity entity, String templateId, Reason reason) {
        this.entity = entity;
        this.templateId = templateId;
        this.reason = reason;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public String getTemplateId() {
        return templateId;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
