package com.exea.entity.api.provider;

import org.bukkit.entity.LivingEntity;

public interface IDamageProvider {

    // #region [Public Methods]
    double calculateDamage(LivingEntity attacker, LivingEntity victim, double baseDamage, String skillId);
    // #endregion
}
