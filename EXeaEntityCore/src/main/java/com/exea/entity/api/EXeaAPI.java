package com.exea.entity.api;

import com.exea.entity.api.behavior.EXeaBehavior;
import com.exea.entity.api.provider.IDamageProvider;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Entry point for other plugins. Add {@code depend: [EXeaEntityCore]} (or softdepend) to your
 * plugin.yml, then use {@code EXeaAPI.getInstance()}. Everything runs on the main thread.
 *
 * <pre>{@code
 * EXeaAPI exea = EXeaAPI.getInstance();
 * exea.registerBehavior("void_overseer", e -> new MyBossBehavior());   // optional
 * EXeaEntity boss = exea.spawn("void_overseer", loc, SpawnOptions.create().health(2000));
 * boss.playAnimation("attack_slam");
 * Location hand = boss.getBoneLocation("hand_r");
 * }</pre>
 * Listen to {@code EXeaEntitySpawnEvent}, {@code EXeaEntityDeathEvent} (kills, damage per player) and
 * {@code EXeaEntityRemoveEvent}.
 */
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

    /** Lets another plugin compute the damage of hits involving EXea entities (null to unset). */
    public void registerDamageProvider(IDamageProvider provider) {
        this.damageProvider = provider;
    }
    // #endregion

    // #region [Spawning and lookup]
    /**
     * Spawns a model with its meta settings, changed by {@code options} (may be null).
     *
     * @return the entity, or null for an unknown model id or a driver type that cannot be spawned
     */
    public EXeaEntity spawn(String modelId, Location location, SpawnOptions options) {
        return manager().spawn(modelId, location, options);
    }

    /** The EXea entity driven by this entity, or null. */
    public EXeaEntity get(Entity entity) {
        return entity == null ? null : manager().getActiveEntity(entity);
    }

    /** Every live EXea entity (a copy). */
    public Collection<EXeaEntity> getAll() {
        List<EXeaEntity> all = new ArrayList<>();
        for (EXActiveEntity e : manager().getActiveEntities().values()) {
            if (!e.isRemoved()) all.add(e);
        }
        return all;
    }

    /** Ids of every loaded model, sorted. */
    public List<String> getModelIds() {
        return manager().getTemplateIds();
    }

    /** Animation names of a loaded model (empty for an unknown id). */
    public Set<String> getAnimations(String modelId) {
        return manager().getAnimationNames(modelId);
    }

    /**
     * Registers how entities behave, under a model id (every entity of that model) or a name used by
     * a model's meta {@code "behavior": "<name>"}. The factory is called once per spawned entity. A
     * {@link SpawnOptions#behavior} wins over this. Null removes the registration.
     */
    public void registerBehavior(String key, Function<EXeaEntity, EXeaBehavior> factory) {
        manager().registerBehavior(key, factory);
    }

    /** Reloads models from the jar and {@code plugins/EXeaEntityCore/models/}. Live entities keep their model. */
    public void reloadModels() {
        manager().reload(com.exea.entity.EXeaEntityPlugin.getInstance());
    }
    // #endregion

    // #region [Shortcuts by driver entity (kept for older callers)]
    /** Same as {@code spawn(templateId, location, null).getDriver()}; null if it fails. */
    public LivingEntity spawnEntity(String templateId, Location location) {
        EXeaEntity e = spawn(templateId, location, null);
        return e == null ? null : e.getDriver();
    }

    public boolean isEXeaEntity(Entity entity) {
        return manager().isActiveEntity(entity);
    }

    public void playAnimation(Entity entity, String animationName) {
        manager().playAnimation(entity, animationName);
    }

    public void stopAnimation(Entity entity) {
        manager().stopAnimation(entity);
    }

    /** Model id of an EXea entity, or null if it is not one. */
    public String getModelId(Entity entity) {
        EXeaEntity e = get(entity);
        return e == null ? null : e.getModelId();
    }

    /** HP removed per attacker UUID so far (copy); empty if it is not an EXea entity. */
    public Map<UUID, Double> getDamageByAttacker(Entity entity) {
        EXeaEntity e = get(entity);
        return e == null ? Map.of() : e.getDamageByAttacker();
    }

    /** True while the death sequence plays (after EXeaEntityDeathEvent, before EXeaEntityRemoveEvent). */
    public boolean isDying(Entity entity) {
        EXeaEntity e = get(entity);
        return e != null && e.isDying();
    }

    /** Kills it the normal way (death event, death animation, removal). False if not EXea or already dying. */
    public boolean kill(Entity entity) {
        return manager().kill(entity);
    }

    /** Removes it at once, no death (remove event with reason REMOVED). False if not an EXea entity. */
    public boolean remove(Entity entity) {
        return manager().remove(entity);
    }
    // #endregion

    private static EntityManager manager() {
        return EntityManager.getInstance();
    }
}
