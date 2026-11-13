package com.exea.entity.core.manager;

import com.exea.entity.EXeaEntityPlugin;
import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.behavior.DefaultBehavior;
import com.exea.entity.api.behavior.EXeaBehavior;
import com.exea.entity.api.event.EXeaEntityDeathEvent;
import com.exea.entity.api.event.EXeaEntityRemoveEvent;
import com.exea.entity.core.animation.AnimationController;
import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.EntityMeta;
import com.exea.entity.core.model.ModelStructure;
import com.exea.entity.core.render.DisplayRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One live EXea entity. The engine part (bones, animation clock, chunk ticket, damage record, death
 * sequence, events) lives here; what it does (which animation when) is its {@link EXeaBehavior}.
 */
public class EXActiveEntity implements EXeaEntity {

    // #region [Variables/Fields]
    private final String templateId;
    private final LivingEntity rootEntity;
    private final ModelStructure modelStructure;
    private final DisplayRenderer renderer;
    private final AnimationController animController;
    private final Map<String, KeyframeTimeline> animationMap = new HashMap<>();
    private EntityMeta meta;
    private EXeaBehavior behavior = new DefaultBehavior();
    private boolean dying = false;
    private int deathTicksRemaining = -1;
    private Chunk trackedChunk = null;
    /** HP removed per attacker (entity UUID), in hit order. */
    private final Map<UUID, Double> damageByAttacker = new LinkedHashMap<>();
    private LivingEntity lastAttacker = null;
    private boolean removed = false;
    // #endregion

    // #region [Properties]
    public void setMeta(EntityMeta meta) {
        this.meta = meta;
    }

    public EntityMeta getMeta() {
        return meta != null ? meta : EntityMeta.defaults(templateId);
    }

    /** Set before {@link #init()}. */
    public void setBehavior(EXeaBehavior behavior) {
        this.behavior = behavior != null ? behavior : new DefaultBehavior();
    }

    public String getTemplateId() {
        return templateId;
    }

    public LivingEntity getRootEntity() {
        return rootEntity;
    }

    public DisplayRenderer getRenderer() {
        return renderer;
    }

    public AnimationController getAnimController() {
        return animController;
    }

    /** False once the entity was removed (the remove event has fired). */
    public boolean isRemoved() {
        return removed;
    }
    // #endregion

    // #region [EXeaEntity]
    @Override
    public String getModelId() {
        return templateId;
    }

    @Override
    public LivingEntity getDriver() {
        return rootEntity;
    }

    @Override
    public boolean isValid() {
        return !removed;
    }

    /** True from the start of the death sequence until the entity is removed. */
    @Override
    public boolean isDying() {
        return dying || removed;
    }

    @Override
    public Set<String> getAnimations() {
        return Collections.unmodifiableSet(animationMap.keySet());
    }

    @Override
    public String getCurrentAnimation() {
        KeyframeTimeline t = animController.getActiveTimeline();
        return t == null ? null : t.getName();
    }

    @Override
    public boolean playAnimation(String animName) {
        return playAnimation(animName, 1f, null);
    }

    @Override
    public boolean playAnimation(String animName, float speed, Loop loop) {
        if (removed || animName == null) return false;
        KeyframeTimeline timeline = animationMap.get(animName.toLowerCase());
        if (timeline == null) return false;
        KeyframeTimeline.LoopMode mode = loop == null ? null : switch (loop) {
            case ONCE -> KeyframeTimeline.LoopMode.ONCE;
            case LOOP -> KeyframeTimeline.LoopMode.LOOP;
            case HOLD -> KeyframeTimeline.LoopMode.HOLD;
        };
        animController.play(timeline, speed, mode);
        return true;
    }

    @Override
    public void stopAnimation() {
        animController.stop();
    }

    @Override
    public Set<String> getBones() {
        Set<String> names = new TreeSet<>();
        for (BoneNode bone : modelStructure.getAllBones()) names.add(bone.getName());
        return names;
    }

    @Override
    public Location getBoneLocation(String bone) {
        return bone == null ? null : animController.boneLocation(bone);
    }

    @Override
    public Map<UUID, Double> getDamageByAttacker() {
        return Map.copyOf(damageByAttacker);
    }

    @Override
    public EXeaBehavior getBehavior() {
        return behavior;
    }

    @Override
    public boolean kill() {
        if (isDying()) return false;
        startDeathSequence();
        return true;
    }

    @Override
    public void remove() {
        EntityManager.getInstance().remove(rootEntity);
    }
    // #endregion

    // #region [Public Methods]
    public EXActiveEntity(String templateId, LivingEntity rootEntity, ModelStructure modelStructure) {
        this.templateId = templateId;
        this.rootEntity = rootEntity;
        this.modelStructure = modelStructure;
        this.renderer = new DisplayRenderer(rootEntity, modelStructure);
        this.animController = new AnimationController(rootEntity, modelStructure);
        this.animController.setOnFinishCallback(this::onAnimationFinished);
        this.animController.setHitHandler(this::animationHit);
    }

    public void registerAnimation(KeyframeTimeline timeline) {
        animationMap.put(timeline.getName().toLowerCase(), timeline);
    }

    /** The driver landed a melee hit on {@code target}. */
    public void onAttack(LivingEntity target) {
        if (isDying()) return;
        safe("onAttack", () -> behavior.onAttack(this, target));
    }

    /**
     * Records a hit that is about to be applied (called at HIGHEST, after every damage modifier) and
     * plays the hurt feedback. A lethal hit is handled by the caller with {@link #startDeathSequence}.
     *
     * @param attacker who dealt it (projectile shooter for projectiles), or null
     * @param damage   final damage of the hit
     */
    public void recordDamage(LivingEntity attacker, double damage) {
        if (isDying() || damage <= 0) {
            return;
        }
        double taken = Math.min(damage, rootEntity.getHealth());
        if (attacker != null) {
            damageByAttacker.merge(attacker.getUniqueId(), taken, Double::sum);
            lastAttacker = attacker;
        }

        double newHp = Math.max(0, rootEntity.getHealth() - damage);
        if (newHp > 0.001) {
            EntityMeta m = getMeta();
            feedback(m.hurtSound(), 0.8f, 1.2f, m.hurtParticle(), 25, 0.3, 0.5, 0.1);
            if (m.nameVisible()) {
                rootEntity.setCustomName(m.healthName(newHp));
            }
            safe("onDamaged", () -> behavior.onDamaged(this, attacker, damage));
        }
    }

    /** Starts the death sequence; the killer is the last recorded attacker. */
    public void startDeathSequence() {
        startDeathSequence(lastAttacker);
    }

    /**
     * Starts the death sequence once ({@code death_ticks} long, 3 s by default) and fires
     * {@link EXeaEntityDeathEvent}.
     *
     * @param killer who dealt the lethal hit, or null (fire, /kill, API): then the last attacker gets it
     */
    public void startDeathSequence(LivingEntity killer) {
        if (isDying()) {
            return;
        }
        dying = true;
        LivingEntity k = killer != null ? killer : lastAttacker;
        EntityMeta m = getMeta();
        if (rootEntity.isValid() && m.nameVisible()) {
            rootEntity.setCustomName(m.healthName(0));
        }
        Bukkit.getPluginManager().callEvent(new EXeaEntityDeathEvent(rootEntity, k, templateId, damageByAttacker));
        safe("onDeath", () -> behavior.onDeath(this, k));
        deathTicksRemaining = m.deathTicks();
        // The root must stay alive for the death sequence (the manager culls dead roots at once),
        // so it stops taking damage and moving; tick() removes it when the sequence ends.
        rootEntity.setInvulnerable(true);
        if (rootEntity instanceof org.bukkit.entity.Mob mob) {
            mob.setAI(false);
        }
        feedback(m.deathSound(), 1.0f, 0.8f, m.deathParticle(), 40, 0.5, 0.8, 0.2);
        if (animationMap.containsKey("death")) {
            playAnimation("death");
        }
    }

    public void init() {
        renderer.spawnBones();
        updateChunkTicket();
        if (animationMap.containsKey("idle")) {
            playAnimation("idle");
        }
        safe("onSpawn", () -> behavior.onSpawn(this));
    }

    public void tick(float deltaSeconds) {
        if (removed) {
            return;
        }
        if (deathTicksRemaining > 0) {
            deathTicksRemaining--;
            renderer.updatePositions();
            animController.tick(deltaSeconds);
            if (deathTicksRemaining == 0) {
                destroy(EXeaEntityRemoveEvent.Reason.DIED);
                if (rootEntity.isValid()) {
                    rootEntity.remove();
                }
            }
            return;
        }

        if (!rootEntity.isValid() || rootEntity.isDead()) {
            destroy(dying ? EXeaEntityRemoveEvent.Reason.DIED : EXeaEntityRemoveEvent.Reason.DRIVER_LOST);
            return;
        }

        updateChunkTicket();
        renderer.updatePositions();
        if (!dying) {
            safe("onTick", () -> behavior.onTick(this));
        }
        animController.tick(deltaSeconds);
    }

    /**
     * Removes the bones and releases the chunk ticket. The first call fires
     * {@link EXeaEntityRemoveEvent} (before cleanup, so the driver can still be read); later calls do
     * nothing. The caller removes the driver entity if it should go too.
     */
    public void destroy(EXeaEntityRemoveEvent.Reason reason) {
        if (removed) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new EXeaEntityRemoveEvent(rootEntity, templateId, reason));
        safe("onRemove", () -> behavior.onRemove(this, reason));
        removed = true;
        releaseChunkTicket();
        animController.stop();
        renderer.removeAll();
    }
    // #endregion

    // #region [Private Methods]
    /**
     * A {@code hit} line of an animation's timeline keyframe: damages living entities within
     * {@code radius} of the bone (or driver) and inside the {@code cone} in front of the body. The
     * damage goes through the normal damage events with the driver as attacker and the animation name
     * as skill id ({@code EXeaEntityDamageEvent#getSkillId}, {@code IDamageProvider}). Skips the driver,
     * other EXea entities, armor stands, and players in creative or spectator.
     */
    private void animationHit(String animation, com.exea.entity.core.timeline.TimelineEvent event, Location center) {
        if (isDying() || event.getDamage() <= 0 || !rootEntity.isValid()) return;
        double r = event.getRadius();
        double halfCone = Math.toRadians(Math.min(360, Math.max(0, event.getCone())) / 2.0);
        float yaw = rootEntity.getBodyYaw();
        org.bukkit.util.Vector forward = new org.bukkit.util.Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        Location origin = rootEntity.getLocation();
        EntityManager manager = EntityManager.getInstance();
        for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, r, r, r)) {
            if (!(e instanceof LivingEntity target) || target == rootEntity || target.isDead()
                    || target instanceof org.bukkit.entity.ArmorStand || manager.isActiveEntity(target)) continue;
            if (target instanceof org.bukkit.entity.Player p
                    && (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR)) continue;
            if (target.getBoundingBox().getCenter().distanceSquared(center.toVector()) > r * r
                    && target.getLocation().distanceSquared(center) > r * r) continue;
            org.bukkit.util.Vector to = target.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (halfCone < Math.PI && to.lengthSquared() > 1e-6 && forward.angle(to) > halfCone) continue;
            manager.runAsSkill(animation, () -> target.damage(event.getDamage(), rootEntity));
            if (event.getKnockback() > 0 && to.lengthSquared() > 1e-6) {
                target.setVelocity(target.getVelocity().add(to.normalize().multiply(event.getKnockback()).setY(0.3)));
            }
        }
    }

    private void feedback(String sound, float volume, float pitch, Particle particle, int count,
                          double spreadXz, double spreadY, double speed) {
        Location loc = rootEntity.getLocation();
        if (sound != null && !sound.isBlank()) {
            loc.getWorld().playSound(loc, sound, volume, pitch);
        }
        if (particle != null) {
            try {
                loc.getWorld().spawnParticle(particle, loc.clone().add(0, 1.5, 0), count, spreadXz, spreadY, spreadXz, speed);
            } catch (IllegalArgumentException ignored) {
                // A particle that needs extra data (dust colour, block...) cannot be set from meta.
            }
        }
    }

    /** Runs behavior code so that a bug in another plugin's behavior cannot break the tick loop. */
    private void safe(String hook, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            EXeaEntityPlugin plugin = EXeaEntityPlugin.getInstance();
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[EXea] Behavior " + behavior.getClass().getName() + "."
                        + hook + " failed for " + templateId + " " + rootEntity.getUniqueId(), t);
            }
        }
    }

    private void updateChunkTicket() {
        if (!rootEntity.isValid() || rootEntity.isDead()) {
            return;
        }
        try {
            Chunk chunk = rootEntity.getLocation().getChunk();
            if (trackedChunk == null || !trackedChunk.equals(chunk)) {
                releaseChunkTicket();
                trackedChunk = chunk;
                EXeaEntityPlugin plugin = EXeaEntityPlugin.getInstance();
                if (plugin != null && trackedChunk != null) {
                    ChunkTickets.acquire(plugin, trackedChunk);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseChunkTicket() {
        if (trackedChunk != null) {
            try {
                EXeaEntityPlugin plugin = EXeaEntityPlugin.getInstance();
                if (plugin != null) {
                    ChunkTickets.release(plugin, trackedChunk);
                }
            } catch (Throwable ignored) {
            }
            trackedChunk = null;
        }
    }

    private void onAnimationFinished(String finishedAnim) {
        if ("death".equalsIgnoreCase(finishedAnim) && dying) {
            destroy(EXeaEntityRemoveEvent.Reason.DIED);
            if (rootEntity.isValid()) {
                rootEntity.remove();
            }
        } else if (!dying && !removed) {
            safe("onAnimationEnd", () -> behavior.onAnimationEnd(this, finishedAnim));
        }
    }
    // #endregion
}
