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
    private com.exea.entity.core.pack.PackServer packServer;
    private com.exea.entity.core.live.LiveLink liveLink;
    // #endregion

    // #region [Properties]
    public static EXeaEntityPlugin getInstance() {
        return instance;
    }

    public com.exea.entity.core.pack.PackServer getPackServer() {
        return packServer;
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

        saveDefaultConfig();
        packServer = new com.exea.entity.core.pack.PackServer(this);
        getServer().getPluginManager().registerEvents(packServer, this);
        packServer.start(getFile());
        com.exea.entity.core.effect.EffectManager.get().start(this);
        com.exea.entity.core.skill.SkillManager.get().start(this);
        liveLink = new com.exea.entity.core.live.LiveLink(this);
        liveLink.start();

        getLogger().info("EXeaEntityCore v" + getDescription().getVersion() + " initialized successfully!");
    }

    @Override
    public void onDisable() {
        if (liveLink != null) liveLink.stop();
        com.exea.entity.core.effect.EffectManager.get().shutdown();
        EntityManager.getInstance().stopTicking();
        if (packServer != null) packServer.stop();
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
        // Hits dealt by an animation's "hit" event carry the animation name as skill id.
        String skill = EntityManager.getInstance().getCurrentSkill();
        EXeaEntityDamageEvent customEvent = new EXeaEntityDamageEvent(attacker, victim, baseDamage, skill != null ? skill : "melee");
        Bukkit.getPluginManager().callEvent(customEvent);

        if (customEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        // Moveset "ai": {"melee": false}: the driver's own melee hits do nothing, only skills deal damage.
        if (isEXeaAttacker && skill == null) {
            EXActiveEntity a = EntityManager.getInstance().getActiveEntity(attacker);
            if (a != null && a.getSkillController() != null && !a.getSkillController().moveset.ai.melee()) {
                event.setCancelled(true);
                return;
            }
        }

        IDamageProvider provider = EXeaAPI.getInstance().getDamageProvider();
        if (provider != null && attacker != null) {
            double finalDamage = provider.calculateDamage(attacker, victim, customEvent.getDamage(), customEvent.getSkillId());
            event.setDamage(finalDamage);
        } else {
            event.setDamage(customEvent.getDamage());
        }

        if (isEXeaAttacker && skill == null) {
            EXActiveEntity activeAttacker = EntityManager.getInstance().getActiveEntity(attacker);
            if (activeAttacker != null) {
                activeAttacker.onAttack(victim);
            }
        }
    }

    /**
     * Drivers can be any mob (a zombie or skeleton driver would burn in daylight). Sunlight fires a
     * plain EntityCombustEvent; fire from blocks or attacks (subclasses) still applies.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDriverCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getClass() == org.bukkit.event.entity.EntityCombustEvent.class
                && EntityManager.getInstance().isActiveEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Runs after every damage modifier (HIGHEST): records who dealt how much (for
     * {@code EXeaEntityDeathEvent#getDamageByAttacker}) and plays the hurt feedback. A hit that would
     * kill an EXea entity is cancelled and the death sequence plays instead: if the root actually
     * died, the tick loop would remove it on the next tick and the 3-second death animation would
     * never be seen. Covers all damage causes (melee, arrows, fire, fall...); /kill bypasses damage
     * events and goes through {@link #onEntityDeath}.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        EXActiveEntity active = EntityManager.getInstance().getActiveEntity(victim);
        if (active == null) return;

        if (active.isDying()) {
            event.setCancelled(true);
            return;
        }
        LivingEntity attacker = event instanceof EntityDamageByEntityEvent byEntity ? attackerOf(byEntity.getDamager()) : null;
        active.recordDamage(attacker, event.getFinalDamage());
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            victim.setHealth(Math.min(victim.getHealth(), 1.0));
            active.startDeathSequence(attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        EXActiveEntity active = EntityManager.getInstance().getActiveEntity(event.getEntity());
        if (active != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            // /kill and similar: the driver is already dead, so the tick loop removes it next tick.
            // Still fire the death event (killer = last attacker) so other plugins count the kill.
            active.startDeathSequence();
        }
    }

    /** The living entity behind a damager: itself, or the shooter of a projectile. */
    private static LivingEntity attackerOf(org.bukkit.entity.Entity damager) {
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) return shooter;
        return null;
    }

    /**
     * EXea roots and bones are non-persistent, so anything tagged that comes back from disk is a
     * leftover from an older build (or a crash before this fix) with no live controller. Remove it.
     */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            java.util.Set<String> tags = e.getScoreboardTags();
            if (tags.contains("exea_bone") || tags.contains("exea_entity") || tags.contains(com.exea.entity.core.effect.EffectManager.TAG)) {
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
