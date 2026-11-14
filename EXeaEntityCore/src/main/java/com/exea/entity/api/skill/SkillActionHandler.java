package com.exea.entity.api.skill;

/**
 * An action type added by another plugin: moveset actions with {@code "type": "<name>"} call it at
 * their time. Register with {@code EXeaAPI.registerSkillAction("chain_lightning", ctx -> ...)}.
 */
@FunctionalInterface
public interface SkillActionHandler {

    void run(SkillActionContext context);
}