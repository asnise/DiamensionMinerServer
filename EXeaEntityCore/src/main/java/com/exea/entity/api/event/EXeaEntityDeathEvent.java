package com.exea.entity.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.UUID;

/**
 * Fired once when an EXea entity dies, at the start of its death sequence (the death animation then
 * plays and an {@link EXeaEntityRemoveEvent} with reason DIED follows). Use this instead of
 * {@code EntityDeathEvent}: EXea cancels the lethal hit so the driver stays alive for the animation,
 * so the vanilla death event normally never fires for EXea entities.
 */
public class EXeaEntityDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity entity;
    private final LivingEntity killer;
    private final String entityTemplateId;
    private final Map<UUID, Double> damageByAttacker;

    public EXeaEntityDeathEvent(LivingEntity entity, LivingEntity killer, String entityTemplateId,
                                Map<UUID, Double> damageByAttacker) {
        this.entity = entity;
        this.killer = killer;
        this.entityTemplateId = entityTemplateId;
        this.damageByAttacker = Map.copyOf(damageByAttacker);
    }

    public LivingEntity getEntity() {
        return entity;
    }

    /**
     * Who dealt the lethal hit (a projectile counts for its shooter). If that hit had no attacker
     * (fire, fall, /kill, API kill), whoever hit it last; null if nobody ever did.
     */
    public LivingEntity getKiller() {
        return killer;
    }

    public String getEntityTemplateId() {
        return entityTemplateId;
    }

    /**
     * Health removed by each attacker over the entity's life (entity UUID → HP, capped at the health
     * the entity had left at each hit, so the values add up to at most its max health).
     */
    public Map<UUID, Double> getDamageByAttacker() {
        return damageByAttacker;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
