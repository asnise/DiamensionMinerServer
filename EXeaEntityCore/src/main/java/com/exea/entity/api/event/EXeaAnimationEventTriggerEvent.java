package com.exea.entity.api.event;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class EXeaAnimationEventTriggerEvent extends Event {

    // #region [Variables/Fields]
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity entity;
    private final String animationName;
    private final String eventTag;
    private final Location triggerLocation;
    // #endregion

    // #region [Properties]
    public LivingEntity getEntity() {
        return entity;
    }

    public String getAnimationName() {
        return animationName;
    }

    public String getEventTag() {
        return eventTag;
    }

    public Location getTriggerLocation() {
        return triggerLocation;
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
    public EXeaAnimationEventTriggerEvent(LivingEntity entity, String animationName, String eventTag, Location triggerLocation) {
        this.entity = entity;
        this.animationName = animationName;
        this.eventTag = eventTag;
        this.triggerLocation = triggerLocation;
    }
    // #endregion
}
