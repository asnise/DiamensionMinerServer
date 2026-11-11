package com.exea.entity.core.manager;

import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import com.exea.entity.core.timeline.TimelineEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.exea.entity.core.model.ModelLoader;

public class EntityManager {

    // #region [Variables/Fields]
    private static EntityManager instance;
    private final Map<UUID, EXActiveEntity> activeEntities = new ConcurrentHashMap<>();
    private final Map<String, ModelStructure> modelTemplates = new HashMap<>();
    private final Map<String, Map<String, KeyframeTimeline>> animationTemplates = new HashMap<>();
    private BukkitTask tickTask;
    // #endregion

    // #region [Properties]
    public static EntityManager getInstance() {
        if (instance == null) {
            instance = new EntityManager();
        }
        return instance;
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
        registerDefaultVoidOverseer(plugin);

        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            float delta = 0.05f;
            Iterator<Map.Entry<UUID, EXActiveEntity>> it = activeEntities.entrySet().iterator();
            while (it.hasNext()) {
                EXActiveEntity active = it.next().getValue();
                if (!active.getRootEntity().isValid() || active.getRootEntity().isDead()) {
                    active.destroy();
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
        registerDefaultVoidOverseer(plugin);
    }

    public void stopTicking() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (EXActiveEntity active : activeEntities.values()) {
            active.destroy();
        }
        activeEntities.clear();
        cleanupOrphanedEntities();
    }

    public LivingEntity spawn(String templateId, Location location) {
        return spawn(templateId, location, true);
    }

    public LivingEntity spawn(String templateId, Location location, boolean enableAI) {
        ModelStructure model = modelTemplates.get(templateId);
        if (model == null) {
            return null;
        }

        ModelStructure instanceModel = model.instantiate();

        LivingEntity root;
        if (enableAI) {
            org.bukkit.entity.Husk husk = (org.bukkit.entity.Husk) location.getWorld().spawnEntity(location, EntityType.HUSK);
            husk.setInvisible(true);
            husk.setSilent(true);
            husk.setPersistent(false);
            husk.setRemoveWhenFarAway(false);
            if (husk.getEquipment() != null) {
                husk.getEquipment().clear();
            }
            try {
                if (husk.getAttribute(org.bukkit.attribute.Attribute.SCALE) != null) {
                    husk.getAttribute(org.bukkit.attribute.Attribute.SCALE).setBaseValue(1.25);
                }
                if (husk.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
                    husk.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(250.0);
                    husk.setHealth(250.0);
                }
                if (husk.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE) != null) {
                    husk.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE).setBaseValue(12.0);
                }
                if (husk.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED) != null) {
                    husk.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).setBaseValue(0.26);
                }
                if (husk.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE) != null) {
                    husk.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE).setBaseValue(32.0);
                }
            } catch (Throwable ignored) {
            }
            husk.setCustomName(org.bukkit.ChatColor.DARK_PURPLE + "Void Overseer " + org.bukkit.ChatColor.GREEN + "[250/250]");
            husk.setCustomNameVisible(true);
            husk.addScoreboardTag("exea_entity");
            root = husk;
        } else {
            org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setPersistent(false);
            stand.setCustomName(org.bukkit.ChatColor.DARK_PURPLE + "Void Overseer [Dummy]");
            stand.setCustomNameVisible(true);
            stand.addScoreboardTag("exea_entity");
            root = stand;
        }

        EXActiveEntity active = new EXActiveEntity(templateId, root, instanceModel);

        Map<String, KeyframeTimeline> anims = animationTemplates.get(templateId);
        if (anims != null) {
            for (KeyframeTimeline timeline : anims.values()) {
                active.registerAnimation(timeline);
            }
        }

        active.init();
        activeEntities.put(root.getUniqueId(), active);
        return root;
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

    public void killAll() {
        for (EXActiveEntity active : activeEntities.values()) {
            active.destroy();
            if (active.getRootEntity().isValid()) {
                active.getRootEntity().remove();
            }
        }
        activeEntities.clear();
        cleanupOrphanedEntities();
    }

    public void cleanupOrphanedEntities() {
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getScoreboardTags().contains("exea_entity") || e.getScoreboardTags().contains("exea_bone")) {
                    e.remove();
                    continue;
                }
                if (e instanceof org.bukkit.entity.ItemDisplay id) {
                    org.bukkit.inventory.ItemStack item = id.getItemStack();
                    if (item != null && item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
                        int cmd = item.getItemMeta().getCustomModelData();
                        if (cmd >= 1001 && cmd <= 1022) {
                            id.remove();
                        }
                    }
                } else if (e instanceof org.bukkit.entity.ArmorStand stand) {
                    if (stand.getCustomName() != null && stand.getCustomName().contains("Void Overseer")) {
                        stand.remove();
                    }
                } else if (e instanceof org.bukkit.entity.Husk husk) {
                    if (husk.getCustomName() != null && husk.getCustomName().contains("Void Overseer")) {
                        husk.remove();
                    }
                }
            }
        }
    }
    // #endregion

    // #region [Private Methods]
    private void registerDefaultVoidOverseer(Plugin plugin) {
        boolean loadedFromDisk = false;

        try {
            File modelsDir = new File(plugin.getDataFolder(), "models");
            File bonesFile = new File(modelsDir, "void_overseer_bones.json");
            File animsFile = new File(modelsDir, "void_overseer_anims.json");

            if (bonesFile.exists() && animsFile.exists()) {
                try (InputStream bs = new FileInputStream(bonesFile);
                     InputStream as = new FileInputStream(animsFile)) {
                    ModelStructure model = ModelLoader.loadModel("void_overseer", bs);
                    Map<String, KeyframeTimeline> anims = ModelLoader.loadAnimations(as);
                    if (model != null && anims != null) {
                        modelTemplates.put("void_overseer", model);
                        animationTemplates.put("void_overseer", anims);
                        loadedFromDisk = true;
                        plugin.getLogger().info("[EXea] Loaded Void Overseer with " + anims.size() + " animations from disk!");
                    }
                }
            }

            if (!loadedFromDisk) {
                InputStream bs = plugin.getResource("models/void_overseer_bones.json");
                InputStream as = plugin.getResource("models/void_overseer_anims.json");
                if (bs != null && as != null) {
                    try (bs; as) {
                        ModelStructure model = ModelLoader.loadModel("void_overseer", bs);
                        Map<String, KeyframeTimeline> anims = ModelLoader.loadAnimations(as);
                        if (model != null && anims != null) {
                            modelTemplates.put("void_overseer", model);
                            animationTemplates.put("void_overseer", anims);
                            loadedFromDisk = true;
                            plugin.getLogger().info("[EXea] Loaded Void Overseer with " + anims.size() + " animations from plugin jar!");
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] Could not load model from JSON, using code fallback: " + e.getMessage());
        }

        if (!loadedFromDisk) {
            registerHardcodedVoidOverseer();
        }
    }

    private void registerHardcodedVoidOverseer() {
        List<BoneNode> bones = new ArrayList<>();

        BoneNode body = new BoneNode("body", new Vector3f(0, 24, 0), new Vector3f(0, 0, 0), 1001);
        body.setHasMesh(false);

        BoneNode torso = new BoneNode("torso", new Vector3f(0, 24, 0), new Vector3f(0, 0, 0), 1002);
        body.addChild(torso);

        BoneNode core = new BoneNode("core", new Vector3f(0, 29.2f, -1.6f), new Vector3f(0, 0, 0), 1003);
        torso.addChild(core);

        BoneNode headOptions = new BoneNode("head_options", new Vector3f(0, 40, 0), new Vector3f(0, 0, 0), 1004);
        headOptions.setHasMesh(false);
        torso.addChild(headOptions);

        BoneNode head1 = new BoneNode("head_1_riftarbiter", new Vector3f(0, 40, 0), new Vector3f(0, 0, 0), 1005);
        headOptions.addChild(head1);

        BoneNode shoulderL = new BoneNode("shoulder_l", new Vector3f(9, 39, 0), new Vector3f(0, 0, 0), 1008);
        BoneNode shoulderR = new BoneNode("shoulder_r", new Vector3f(-9, 39, 0), new Vector3f(0, 0, 0), 1009);
        torso.addChild(shoulderL);
        torso.addChild(shoulderR);

        BoneNode armL = new BoneNode("arm_l", new Vector3f(13, 35, 0), new Vector3f(0, 0, 0), 1010);
        BoneNode handL = new BoneNode("hand_l", new Vector3f(13, 19, 0), new Vector3f(0, 0, 0), 1011);
        armL.addChild(handL);
        handL.addChild(new BoneNode("thumb_l", new Vector3f(10, 13.6f, -0.5f), new Vector3f(0, 0, 0), 1012));
        handL.addChild(new BoneNode("index_l", new Vector3f(11.52f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1013));
        handL.addChild(new BoneNode("middle_l", new Vector3f(13.62f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1014));
        handL.addChild(new BoneNode("ring_l", new Vector3f(16.02f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1015));
        torso.addChild(armL);

        BoneNode armR = new BoneNode("arm_r", new Vector3f(-13, 35, 0), new Vector3f(0, 0, 0), 1016);
        BoneNode handR = new BoneNode("hand_r", new Vector3f(-13, 19, 0), new Vector3f(0, 0, 0), 1017);
        armR.addChild(handR);
        handR.addChild(new BoneNode("thumb_r", new Vector3f(-10, 13.6f, -0.5f), new Vector3f(0, 0, 0), 1018));
        handR.addChild(new BoneNode("index_r", new Vector3f(-11.52f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1019));
        handR.addChild(new BoneNode("middle_r", new Vector3f(-13.62f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1020));
        handR.addChild(new BoneNode("ring_r", new Vector3f(-16.02f, 14.89f, -2.62f), new Vector3f(0, 0, 0), 1021));
        torso.addChild(armR);

        BoneNode orbiters = new BoneNode("orbiters", new Vector3f(0, 28, 0), new Vector3f(0, 0, 0), 1022);
        body.addChild(orbiters);

        bones.add(body);

        ModelStructure voidOverseerModel = new ModelStructure("void_overseer", bones);
        modelTemplates.put("void_overseer", voidOverseerModel);

        Map<String, KeyframeTimeline> anims = new HashMap<>();
        KeyframeTimeline idle = new KeyframeTimeline("idle", 3.0f, KeyframeTimeline.LoopMode.LOOP);
        anims.put("idle", idle);
        animationTemplates.put("void_overseer", anims);
    }
    // #endregion
}
