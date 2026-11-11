package com.exea.entity.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class EXeaEntityDamageEvent extends Event implements Cancellable {

    // #region [Variables/Fields]
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity attacker;
    private final LivingEntity victim;
    private final String skillId;
    private double damage;
    private boolean cancelled;
    // #endregion

    // #region [Properties]
    public LivingEntity getAttacker() {
        return attacker;
    }

    public LivingEntity getVictim() {
        return victim;
    }

    public String getSkillId() {
        return skillId;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
    public EXeaEntityDamageEvent(LivingEntity attacker, LivingEntity victim, double damage, String skillId) {
        this.attacker = attacker;
        this.victim = victim;
        this.damage = damage;
        this.skillId = skillId;
        this.cancelled = false;
    }
    // #endregion
}
