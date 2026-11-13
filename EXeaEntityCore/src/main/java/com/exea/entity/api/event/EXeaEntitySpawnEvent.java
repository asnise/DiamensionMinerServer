package com.exea.entity.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once after an EXea entity is spawned: its driver entity exists, its bones are spawned and
 * its idle animation has started. Not cancellable; remove the entity through the API to undo it.
 */
public class EXeaEntitySpawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity entity;
    private final String templateId;

    public EXeaEntitySpawnEvent(LivingEntity entity, String templateId) {
        this.entity = entity;
        this.templateId = templateId;
    }

    /** The driver entity (hitbox, health, AI). Same object the spawn call returned. */
    public LivingEntity getEntity() {
        return entity;
    }

    /** Model id, e.g. {@code void_overseer}. */
    public String getTemplateId() {
        return templateId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
