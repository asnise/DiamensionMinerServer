package com.exea.entity.api;

import com.exea.entity.api.provider.IDamageProvider;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

public class EXeaAPI {

    // #region [Variables/Fields]
    private static EXeaAPI instance;
    private IDamageProvider damageProvider;
    // #endregion

    // #region [Properties]
    public static EXeaAPI getInstance() {
        if (instance == null) {
            instance = new EXeaAPI();
        }
        return instance;
    }

    public IDamageProvider getDamageProvider() {
        return damageProvider;
    }

    public void registerDamageProvider(IDamageProvider provider) {
        this.damageProvider = provider;
    }
    // #endregion

    // #region [Public Methods]
    public LivingEntity spawnEntity(String templateId, Location location) {
        return com.exea.entity.core.manager.EntityManager.getInstance().spawn(templateId, location);
    }

    public boolean isEXeaEntity(Entity entity) {
        return com.exea.entity.core.manager.EntityManager.getInstance().isActiveEntity(entity);
    }

    public void playAnimation(Entity entity, String animationName) {
        com.exea.entity.core.manager.EntityManager.getInstance().playAnimation(entity, animationName);
    }

    public void stopAnimation(Entity entity) {
        com.exea.entity.core.manager.EntityManager.getInstance().stopAnimation(entity);
    }
    // #endregion
}
