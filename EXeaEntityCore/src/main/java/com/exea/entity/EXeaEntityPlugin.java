package com.exea.entity;

import com.exea.entity.api.EXeaAPI;
import com.exea.entity.api.event.EXeaEntityDamageEvent;
import com.exea.entity.api.provider.IDamageProvider;
import com.exea.entity.core.command.EXeaCommandExecutor;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class EXeaEntityPlugin extends JavaPlugin implements Listener {

    // #region [Variables/Fields]
    private static EXeaEntityPlugin instance;
    // #endregion

    // #region [Properties]
    public static EXeaEntityPlugin getInstance() {
        return instance;
    }
    // #endregion

    // #region [Unity Methods]
    @Override
    public void onEnable() {
        instance = this;

        PluginCommand cmd = getCommand("exea");
        if (cmd != null) {
            EXeaCommandExecutor executor = new EXeaCommandExecutor();
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(this, this);
        EntityManager.getInstance().startTicking(this);

        getLogger().info("EXeaEntityCore v" + getDescription().getVersion() + " initialized successfully!");
    }

    @Override
    public void onDisable() {
        EntityManager.getInstance().stopTicking();
        getLogger().info("EXeaEntityCore disabled cleanly.");
    }
    // #endregion

    // #region [Public Methods]
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        LivingEntity attacker = null;
        if (event.getDamager() instanceof LivingEntity livingDamager) {
            attacker = livingDamager;
        }

        boolean isEXeaVictim = EntityManager.getInstance().isActiveEntity(victim);
        boolean isEXeaAttacker = attacker != null && EntityManager.getInstance().isActiveEntity(attacker);

        if (!isEXeaVictim && !isEXeaAttacker) {
            return;
        }

        double baseDamage = event.getDamage();
        EXeaEntityDamageEvent customEvent = new EXeaEntityDamageEvent(attacker, victim, baseDamage, "melee");
        Bukkit.getPluginManager().callEvent(customEvent);

        if (customEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        IDamageProvider provider = EXeaAPI.getInstance().getDamageProvider();
        if (provider != null && attacker != null) {
            double finalDamage = provider.calculateDamage(attacker, victim, customEvent.getDamage(), customEvent.getSkillId());
            event.setDamage(finalDamage);
        } else {
            event.setDamage(customEvent.getDamage());
        }

        if (isEXeaAttacker) {
            EXActiveEntity activeAttacker = EntityManager.getInstance().getActiveEntity(attacker);
            if (activeAttacker != null) {
                activeAttacker.triggerAttack();
            }
        }

        if (isEXeaVictim) {
            EXActiveEntity activeVictim = EntityManager.getInstance().getActiveEntity(victim);
            if (activeVictim != null) {
                activeVictim.onDamaged(event.getFinalDamage());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (EntityManager.getInstance().isActiveEntity(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            EXActiveEntity active = EntityManager.getInstance().getActiveEntity(event.getEntity());
            if (active != null) {
                active.startDeathSequence();
            }
        }
    }

    /**
     * EXea roots and bones are non-persistent, so anything tagged that comes back from disk is a
     * leftover from an older build (or a crash before this fix) with no live controller. Remove it.
     */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            java.util.Set<String> tags = e.getScoreboardTags();
            if (tags.contains("exea_bone") || tags.contains("exea_entity")) {
                e.remove();
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (EXActiveEntity active : EntityManager.getInstance().getActiveEntities().values()) {
                if (active.getRootEntity().isValid()) {
                    active.getRenderer().forceSync();
                }
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (EXActiveEntity active : EntityManager.getInstance().getActiveEntities().values()) {
                if (active.getRootEntity().isValid()) {
                    active.getRenderer().forceSync();
                }
            }
        }, 5L);
    }
    // #endregion
}
