package com.exea.entity.api.event;

import com.exea.entity.api.EXeaEntity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** An {@code event} action of a moveset skill fired: logic for your plugin to run at that moment. */
public class EXeaSkillEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final EXeaEntity entity;
    private final String skillId;
    private final String tag;
    private final LivingEntity target;
    private final Location location;

    public EXeaSkillEvent(EXeaEntity entity, String skillId, String tag, LivingEntity target, Location location) {
        this.entity = entity;
        this.skillId = skillId;
        this.tag = tag;
        this.target = target;
        this.location = location;
    }

    public EXeaEntity getEntity() { return entity; }
    public String getSkillId() { return skillId; }
    /** The action's {@code tag}. */
    public String getTag() { return tag; }
    /** The skill's target, or null. */
    public LivingEntity getTarget() { return target; }
    /** Where the action happened (its bone / anchor, or the impact point inside a projectile's on_hit). */
    public Location getLocation() { return location.clone(); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}