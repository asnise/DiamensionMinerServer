package com.exea.entity.core.manager;

import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.exea.entity.core.model.ModelLoader;
import com.exea.entity.core.model.EntityMeta;
import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.SpawnOptions;
import com.exea.entity.api.behavior.DefaultBehavior;
import com.exea.entity.api.behavior.EXeaBehavior;
import java.util.function.Function;
import com.exea.entity.api.event.EXeaEntityRemoveEvent;
import com.exea.entity.api.event.EXeaEntitySpawnEvent;

public class EntityManager {

    // #region [Variables/Fields]
    private static EntityManager instance;
    private final Map<UUID, EXActiveEntity> activeEntities = new ConcurrentHashMap<>();
    private final Map<String, ModelStructure> modelTemplates = new HashMap<>();
    private final Map<String, Map<String, KeyframeTimeline>> animationTemplates = new HashMap<>();
    private final Map<String, EntityMeta> metaTemplates = new HashMap<>();
    private final Map<String, Function<EXeaEntity, EXeaBehavior>> behaviorFactories = new ConcurrentHashMap<>();
    private BukkitTask tickTask;
    // #endregion

    // #region [Properties]
    public static EntityManager getInstance() {
        if (instance == null) {
            instance = new EntityManager();
        }
        return instance;
    }

    /** Ids that /exea spawn accepts, sorted. */
    public List<String> getTemplateIds() {
        List<String> ids = new ArrayList<>(modelTemplates.keySet());
        Collections.sort(ids);
        return ids;
    }

    public Set<String> getAnimationNames(String templateId) {
        Map<String, KeyframeTimeline> anims = animationTemplates.get(templateId);
        return anims == null ? Set.of() : new TreeSet<>(anims.keySet());
    }

    public Map<UUID, EXActiveEntity> getActiveEntities() {
        return activeEntities;
    }
    // #endregion

    // #region [Public Methods]
    public void startTicking(Plugin plugin) {
        if (tickTask != null) {
            tickTask.cancel();
        }

        cleanupOrphanedEntities();
        registerTemplates(plugin);

        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            float delta = 0.05f;
            Iterator<Map.Entry<UUID, EXActiveEntity>> it = activeEntities.entrySet().iterator();
            while (it.hasNext()) {
                EXActiveEntity active = it.next().getValue();
                if (active.isRemoved()) {
                    it.remove();
                } else if (!active.getRootEntity().isValid() || active.getRootEntity().isDead()) {
                    active.destroy(active.isDying() ? EXeaEntityRemoveEvent.Reason.DIED : EXeaEntityRemoveEvent.Reason.DRIVER_LOST);
                    it.remove();
                } else {
                    active.tick(delta);
                }
            }
        }, 1L, 1L);
    }

    public void reload(Plugin plugin) {
        modelTemplates.clear();
        animationTemplates.clear();
        metaTemplates.clear();
        registerTemplates(plugin);
    }

    public void stopTicking() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (EXActiveEntity active : activeEntities.values()) {
            active.destroy(EXeaEntityRemoveEvent.Reason.PLUGIN_DISABLE);
        }
        activeEntities.clear();
        cleanupOrphanedEntities();
    }

    public LivingEntity spawn(String templateId, Location location) {
        return spawn(templateId, location, true);
    }

    /** Spawn for /exea: with AI = the model's driver (HUSK by default), without = a statue. */
    public LivingEntity spawn(String templateId, Location location, boolean enableAI) {
        EXActiveEntity e = spawn(templateId, location, SpawnOptions.create().ai(enableAI));
        return e == null ? null : e.getRootEntity();
    }

    /**
     * Spawns an EXea entity: a driver configured from the model meta overridden by {@code options},
     * its bones, its behavior, then fires {@link EXeaEntitySpawnEvent}. Null for an unknown model id
     * or a driver type that is not a spawnable living entity.
     */
    public EXActiveEntity spawn(String templateId, Location location, SpawnOptions options) {
        ModelStructure model = modelTemplates.get(templateId);
        if (model == null || location.getWorld() == null) {
            return null;
        }
        SpawnOptions o = options != null ? options : SpawnOptions.create();
        boolean ai = o.ai() == null || o.ai();
        EntityMeta meta = metaTemplates.getOrDefault(templateId, EntityMeta.defaults(templateId)).with(o);

        EntityType type = meta.driver() != null ? meta.driver() : ai ? EntityType.HUSK : EntityType.ARMOR_STAND;
        if (!type.isAlive() || !type.isSpawnable()) {
            return null;
        }

        ModelStructure instanceModel = model.instantiate();
        for (BoneNode bone : instanceModel.getAllBones()) {
            bone.setGlow(meta.glowBones().contains(bone.getName()));
        }

        // randomizeData=false: no baby zombies, no chicken jockeys, no random equipment.
        Entity spawned = location.getWorld().spawnEntity(location, type, false);
        if (!(spawned instanceof LivingEntity root)) {
            spawned.remove();
            return null;
        }
        configureDriver(root, meta, ai);

        EXActiveEntity active = new EXActiveEntity(templateId, root, instanceModel);
        active.setMeta(meta);
        Map<String, KeyframeTimeline> anims = animationTemplates.get(templateId);
        if (anims != null) {
            for (KeyframeTimeline timeline : anims.values()) {
                active.registerAnimation(timeline);
            }
        }
        com.exea.entity.core.skill.Moveset moveset = com.exea.entity.core.skill.SkillManager.get().forModel(templateId, meta.moveset());
        if (moveset != null) {
            boolean auto = o.autoSkills() != null ? o.autoSkills() : moveset.ai.auto();
            active.setSkillController(new com.exea.entity.core.skill.SkillController(active, moveset, auto));
        }
        active.setBehavior(createBehavior(templateId, meta, o, active));

        activeEntities.put(root.getUniqueId(), active);
        active.init();
        org.bukkit.Bukkit.getPluginManager().callEvent(new EXeaEntitySpawnEvent(root, templateId));
        return active;
    }

    /**
     * Registers a behavior factory under a key: a model id (used for every entity of that model) or
     * any name a model's meta {@code behavior} points to. Null removes it. Replaces an earlier one.
     */
    public void registerBehavior(String key, Function<EXeaEntity, EXeaBehavior> factory) {
        if (factory == null) behaviorFactories.remove(key.toLowerCase());
        else behaviorFactories.put(key.toLowerCase(), factory);
    }

    /** Order: spawn option, meta "behavior" key, model id key, MovesetBehavior (model has a moveset), DefaultBehavior. */
    private EXeaBehavior createBehavior(String templateId, EntityMeta meta, SpawnOptions o, EXActiveEntity active) {
        if (o.behavior() != null) return o.behavior();
        Function<EXeaEntity, EXeaBehavior> f = null;
        if (meta.behavior() != null) f = behaviorFactories.get(meta.behavior().toLowerCase());
        if (f == null) f = behaviorFactories.get(templateId.toLowerCase());
        if (f != null) {
            try {
                EXeaBehavior b = f.apply(active);
                if (b != null) return b;
            } catch (Throwable t) {
                org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                        "[EXea] Behavior factory for " + templateId + " failed; using the default behavior", t);
            }
        }
        if (active.getSkillController() != null) return new com.exea.entity.api.behavior.MovesetBehavior();
        return new DefaultBehavior();
    }

    private static void configureDriver(LivingEntity root, EntityMeta meta, boolean ai) {
        root.setInvisible(true);
        root.setSilent(true);
        root.setPersistent(false);
        root.setRemoveWhenFarAway(false);
        root.setCanPickupItems(false);
        if (root.getEquipment() != null) {
            root.getEquipment().clear();
        }
        if (root instanceof org.bukkit.entity.Ageable ageable) {
            ageable.setAdult();
        }
        if (root instanceof org.bukkit.entity.ArmorStand stand) {
            stand.setVisible(false);
            stand.setGravity(false);
        }
        if (root instanceof org.bukkit.entity.Mob mob) {
            mob.setAI(ai);
        }
        setAttribute(root, org.bukkit.attribute.Attribute.SCALE, meta.hitboxScale());
        if (setAttribute(root, org.bukkit.attribute.Attribute.MAX_HEALTH, meta.health())) {
            root.setHealth(meta.health());
        }
        setAttribute(root, org.bukkit.attribute.Attribute.ATTACK_DAMAGE, meta.attackDamage());
        setAttribute(root, org.bukkit.attribute.Attribute.MOVEMENT_SPEED, meta.movementSpeed());
        setAttribute(root, org.bukkit.attribute.Attribute.FOLLOW_RANGE, meta.followRange());
        boolean statue = root instanceof org.bukkit.entity.ArmorStand;
        root.setCustomName(statue ? meta.nameColor() + meta.displayName() + " [Dummy]" : meta.healthName(meta.health()));
        root.setCustomNameVisible(meta.nameVisible());
        root.addScoreboardTag("exea_entity");
    }

    private static boolean setAttribute(LivingEntity e, org.bukkit.attribute.Attribute attribute, double value) {
        try {
            org.bukkit.attribute.AttributeInstance inst = e.getAttribute(attribute);
            if (inst == null) return false;
            inst.setBaseValue(value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isActiveEntity(Entity entity) {
        return activeEntities.containsKey(entity.getUniqueId());
    }

    public EXActiveEntity getActiveEntity(Entity entity) {
        return activeEntities.get(entity.getUniqueId());
    }

    public void playAnimation(Entity entity, String animName) {
        EXActiveEntity active = activeEntities.get(entity.getUniqueId());
        if (active != null) {
            active.playAnimation(animName);
        }
    }

    public void stopAnimation(Entity entity) {
        EXActiveEntity active = activeEntities.get(entity.getUniqueId());
        if (active != null) {
            active.stopAnimation();
        }
    }

    /** Animation name while an animation hit is being dealt (see EXActiveEntity#animationHit), else null. */
    private String currentSkill;

    /** Runs {@code r} with {@link #getCurrentSkill()} set, so damage events know it came from that animation. */
    public void runAsSkill(String skill, Runnable r) {
        String previous = currentSkill;
        currentSkill = skill;
        try {
            r.run();
        } finally {
            currentSkill = previous;
        }
    }

    public String getCurrentSkill() {
        return currentSkill;
    }

    /** True if the model id was loaded. */
    public boolean hasTemplate(String templateId) {
        return modelTemplates.containsKey(templateId);
    }

    /**
     * Removes one EXea entity at once (no death sequence): fires the remove event with reason
     * REMOVED, then removes its bones and its driver. False if it is not an EXea entity.
     */
    public boolean remove(Entity entity) {
        EXActiveEntity active = activeEntities.remove(entity.getUniqueId());
        if (active == null) return false;
        active.destroy(EXeaEntityRemoveEvent.Reason.REMOVED);
        if (active.getRootEntity().isValid()) {
            active.getRootEntity().remove();
        }
        return true;
    }

    /**
     * Kills one EXea entity the normal way: death event, death animation, then removal. The killer
     * is whoever hit it last. False if it is not an EXea entity or is already dying.
     */
    public boolean kill(Entity entity) {
        EXActiveEntity active = activeEntities.get(entity.getUniqueId());
        return active != null && active.kill();
    }

    public void killAll() {
        for (EXActiveEntity active : activeEntities.values()) {
            active.destroy(EXeaEntityRemoveEvent.Reason.REMOVED);
            if (active.getRootEntity().isValid()) {
                active.getRootEntity().remove();
            }
        }
        activeEntities.clear();
        cleanupOrphanedEntities();
    }

    /**
     * Removes EXea roots and bones that no live EXActiveEntity owns. Matches only this plugin's own
     * scoreboard tags (every root gets "exea_entity", every bone "exea_bone"); it used to also match
     * by custom name and CustomModelData range, which deleted other plugins' entities.
     */
    public void cleanupOrphanedEntities() {
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                Set<String> tags = e.getScoreboardTags();
                if (tags.contains("exea_entity") || tags.contains("exea_bone")) {
                    e.remove();
                }
            }
        }
    }
    // #endregion

    // #region [Private Methods]
    /**
     * Loads every model: {@code <id>_bones.json} + {@code <id>_anims.json} (+ optional {@code <id>_meta.json})
     * from the plugin jar's {@code models/}, then from {@code plugins/EXeaEntityCore/models/}, where a
     * file of the same id wins. With no model at all the plugin still runs; it just has nothing to spawn.
     */
    private void registerTemplates(Plugin plugin) {
        Map<String, String> found = new TreeMap<>(); // id -> where it came from
        try {
            java.net.URL jarUrl = EntityManager.class.getProtectionDomain().getCodeSource().getLocation();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(jarUrl.toURI()))) {
                Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("models/") && name.endsWith("_bones.json")) {
                        String id = name.substring("models/".length(), name.length() - "_bones.json".length());
                        if (loadTemplate(plugin, id, () -> plugin.getResource("models/" + id + "_bones.json"),
                                () -> plugin.getResource("models/" + id + "_anims.json"),
                                () -> plugin.getResource("models/" + id + "_meta.json"))) {
                            found.put(id, "jar");
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] Could not list models in the plugin jar: " + e.getMessage());
        }

        File[] files = new File(plugin.getDataFolder(), "models").listFiles((dir, name) -> name.endsWith("_bones.json"));
        if (files != null) {
            for (File bones : files) {
                String id = bones.getName().substring(0, bones.getName().length() - "_bones.json".length());
                File anims = new File(bones.getParentFile(), id + "_anims.json");
                File meta = new File(bones.getParentFile(), id + "_meta.json");
                if (!anims.exists()) continue;
                if (loadTemplate(plugin, id, () -> open(bones), () -> open(anims), () -> meta.exists() ? open(meta) : null)) {
                    found.put(id, "disk");
                }
            }
        }

        if (found.isEmpty()) {
            plugin.getLogger().warning("[EXea] No models found. Put <id>_bones.json + <id>_anims.json (+ <id>_meta.json) in "
                    + new File(plugin.getDataFolder(), "models").getPath() + " and run /exea reload.");
        } else {
            plugin.getLogger().info("[EXea] Models: " + found);
        }
    }

    private static InputStream open(File f) {
        try {
            return new FileInputStream(f);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean loadTemplate(Plugin plugin, String id, java.util.function.Supplier<InputStream> bones,
                                 java.util.function.Supplier<InputStream> anims, java.util.function.Supplier<InputStream> meta) {
        try (InputStream bs = bones.get(); InputStream as = anims.get(); InputStream ms = meta.get()) {
            if (bs == null || as == null) return false;
            ModelStructure model = ModelLoader.loadModel(id, bs);
            Map<String, KeyframeTimeline> timelines = ModelLoader.loadAnimations(as);
            if (model == null || timelines == null) return false;
            modelTemplates.put(id, model);
            animationTemplates.put(id, timelines);
            metaTemplates.put(id, EntityMeta.load(id, ms));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] Could not load model " + id + ": " + e.getMessage());
            return false;
        }
    }

    // #endregion
}
