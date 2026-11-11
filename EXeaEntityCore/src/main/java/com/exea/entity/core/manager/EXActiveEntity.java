package com.exea.entity.core.manager;

import com.exea.entity.core.animation.AnimationController;
import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.model.ModelStructure;
import com.exea.entity.core.render.DisplayRenderer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import com.exea.entity.EXeaEntityPlugin;

import java.util.HashMap;
import java.util.Map;

public class EXActiveEntity {

    public enum EntityState {
        IDLE,
        MOVING,
        ATTACKING,
        DEAD
    }

    // #region [Variables/Fields]
    private final String templateId;
    private final LivingEntity rootEntity;
    private final ModelStructure modelStructure;
    private final DisplayRenderer renderer;
    private final AnimationController animController;
    private final Map<String, KeyframeTimeline> animationMap = new HashMap<>();
    private EntityState state = EntityState.IDLE;
    private Location lastTickLoc = null;
    private double maxHealth = 250.0;
    private int deathTicksRemaining = -1;
    private Chunk trackedChunk = null;
    // #endregion

    // #region [Properties]
    public String getTemplateId() {
        return templateId;
    }

    public LivingEntity getRootEntity() {
        return rootEntity;
    }

    public EntityState getState() {
        return state;
    }

    public void setState(EntityState state) {
        this.state = state;
    }

    public DisplayRenderer getRenderer() {
        return renderer;
    }

    public AnimationController getAnimController() {
        return animController;
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
        if (rootEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
            this.maxHealth = rootEntity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
        }
    }

    public void registerAnimation(KeyframeTimeline timeline) {
        animationMap.put(timeline.getName().toLowerCase(), timeline);
    }

    public void playAnimation(String animName) {
        KeyframeTimeline timeline = animationMap.get(animName.toLowerCase());
        if (timeline != null) {
            animController.play(timeline);
        }
    }

    public void stopAnimation() {
        animController.stop();
    }

    public void triggerAttack() {
        if (state == EntityState.DEAD || state == EntityState.ATTACKING) {
            return;
        }
        state = EntityState.ATTACKING;
        String[] attacks = {"attack_slash", "attack_double_slash", "attack_slam"};
        String chosen = attacks[java.util.concurrent.ThreadLocalRandom.current().nextInt(attacks.length)];
        if (animationMap.containsKey(chosen)) {
            playAnimation(chosen);
        } else if (animationMap.containsKey("attack_slash")) {
            playAnimation("attack_slash");
        }
    }

    public void onDamaged(double damage) {
        if (state == EntityState.DEAD) {
            return;
        }

        rootEntity.getWorld().playSound(rootEntity.getLocation(), org.bukkit.Sound.ENTITY_WITHER_HURT, 0.8f, 1.2f);
        rootEntity.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, rootEntity.getLocation().add(0, 1.5, 0), 25, 0.3, 0.5, 0.3, 0.1);

        double newHp = Math.max(0, rootEntity.getHealth() - damage);
        rootEntity.setCustomName(org.bukkit.ChatColor.DARK_PURPLE + "Void Overseer " + org.bukkit.ChatColor.RED + "[" + (int) newHp + "/" + (int) maxHealth + "]");

        if (newHp <= 0.001) {
            startDeathSequence();
        }
    }

    public void startDeathSequence() {
        if (state == EntityState.DEAD) {
            return;
        }
        state = EntityState.DEAD;
        deathTicksRemaining = 60;
        // The root must stay alive for the 3 s sequence (the manager culls dead roots at once),
        // so it stops taking damage and moving; tick() removes it when the sequence ends.
        rootEntity.setInvulnerable(true);
        if (rootEntity instanceof org.bukkit.entity.Mob mob) {
            mob.setAI(false);
        }
        rootEntity.getWorld().playSound(rootEntity.getLocation(), org.bukkit.Sound.ENTITY_WITHER_DEATH, 1.0f, 0.8f);
        rootEntity.getWorld().spawnParticle(org.bukkit.Particle.WARPED_SPORE, rootEntity.getLocation().add(0, 1.5, 0), 40, 0.5, 0.8, 0.5, 0.2);
        if (animationMap.containsKey("death")) {
            playAnimation("death");
        }
    }

    /** True from the start of the death sequence until the entity is removed. */
    public boolean isDying() {
        return state == EntityState.DEAD;
    }

    public void init() {
        renderer.spawnBones();
        updateChunkTicket();
        if (animationMap.containsKey("idle")) {
            playAnimation("idle");
        }
    }

    public void tick(float deltaSeconds) {
        if (deathTicksRemaining > 0) {
            deathTicksRemaining--;
            renderer.updatePositions();
            animController.tick(deltaSeconds);
            if (deathTicksRemaining == 0) {
                destroy();
                if (rootEntity.isValid()) {
                    rootEntity.remove();
                }
            }
            return;
        }

        if (!rootEntity.isValid() || rootEntity.isDead()) {
            destroy();
            return;
        }

        updateChunkTicket();
        renderer.updatePositions();

        Location currentLoc = rootEntity.getLocation();
        if (lastTickLoc != null && state != EntityState.ATTACKING && state != EntityState.DEAD) {
            double distSq = currentLoc.distanceSquared(lastTickLoc);
            if (distSq > 0.0015) {
                if (state != EntityState.MOVING) {
                    state = EntityState.MOVING;
                    if (animationMap.containsKey("move")) {
                        playAnimation("move");
                    }
                }
            } else {
                if (state == EntityState.MOVING) {
                    state = EntityState.IDLE;
                    if (animationMap.containsKey("idle")) {
                        playAnimation("idle");
                    }
                }
            }
        }
        lastTickLoc = currentLoc.clone();

        animController.tick(deltaSeconds);
    }

    public void destroy() {
        state = EntityState.DEAD;
        releaseChunkTicket();
        animController.stop();
        renderer.removeAll();
    }
    // #endregion

    // #region [Private Methods]
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
        if (finishedAnim.startsWith("attack_")) {
            state = EntityState.IDLE;
            Location currentLoc = rootEntity.getLocation();
            if (lastTickLoc != null && currentLoc.distanceSquared(lastTickLoc) > 0.0015 && animationMap.containsKey("move")) {
                state = EntityState.MOVING;
                playAnimation("move");
            } else if (animationMap.containsKey("idle")) {
                playAnimation("idle");
            }
        } else if ("death".equalsIgnoreCase(finishedAnim)) {
            destroy();
            if (rootEntity.isValid()) {
                rootEntity.remove();
            }
        } else if (!"idle".equalsIgnoreCase(finishedAnim) && animationMap.containsKey("idle")) {
            playAnimation("idle");
        }
    }
    // #endregion
}
