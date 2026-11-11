package com.exea.entity.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class EXeaEntityDeathEvent extends Event {

    // #region [Variables/Fields]
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity entity;
    private final LivingEntity killer;
    private final String entityTemplateId;
    // #endregion

    // #region [Properties]
    public LivingEntity getEntity() {
        return entity;
    }

    public LivingEntity getKiller() {
        return killer;
    }

    public String getEntityTemplateId() {
        return entityTemplateId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
    // #endregion

    // #region [Public Methods]
    public EXeaEntityDeathEvent(LivingEntity entity, LivingEntity killer, String entityTemplateId) {
        this.entity = entity;
        this.killer = killer;
        this.entityTemplateId = entityTemplateId;
    }
    // #endregion
}
