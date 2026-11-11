package com.exea.entity.core.render;

import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class DisplayRenderer {

    // #region [Variables/Fields]
    private final LivingEntity rootEntity;
    private final ModelStructure modelStructure;
    private final List<ItemDisplay> spawnedDisplays = new ArrayList<>();
    private Location lastLocation = null;
    // #endregion

    // #region [Properties]
    public List<ItemDisplay> getSpawnedDisplays() {
        return spawnedDisplays;
    }
    // #endregion

    // #region [Public Methods]
    public DisplayRenderer(LivingEntity rootEntity, ModelStructure modelStructure) {
        this.rootEntity = rootEntity;
        this.modelStructure = modelStructure;
    }

    public void spawnBones() {
        Location baseLocation = rootEntity.getLocation();

        for (BoneNode rootBone : modelStructure.getRootBones()) {
            spawnBoneRecursive(rootBone, baseLocation);
        }
    }

    public void updatePositions() {
        if (!rootEntity.isValid() || rootEntity.isDead()) {
            return;
        }

        boolean anyInvalid = false;
        for (ItemDisplay display : spawnedDisplays) {
            if (!display.isValid() || display.isDead()) {
                anyInvalid = true;
                break;
            }
        }
        if (anyInvalid) {
            respawnBones();
            return;
        }

        Location currentLoc = rootEntity.getLocation();
        if (lastLocation == null ||
                currentLoc.getWorld() != lastLocation.getWorld() ||
                currentLoc.distanceSquared(lastLocation) > 0.0001 ||
                Math.abs(currentLoc.getYaw() - lastLocation.getYaw()) > 0.05f ||
                Math.abs(currentLoc.getPitch() - lastLocation.getPitch()) > 0.05f) {

            for (ItemDisplay display : spawnedDisplays) {
                if (display.isValid()) {
                    display.setTeleportDuration(1);
                    display.teleport(currentLoc);
                }
            }
            lastLocation = currentLoc.clone();
        }
    }

    public void forceSync() {
        if (!rootEntity.isValid() || rootEntity.isDead()) {
            return;
        }

        boolean anyInvalid = false;
        for (ItemDisplay display : spawnedDisplays) {
            if (!display.isValid() || display.isDead()) {
                anyInvalid = true;
                break;
            }
        }
        if (anyInvalid) {
            respawnBones();
            return;
        }

        Location currentLoc = rootEntity.getLocation();
        for (ItemDisplay display : spawnedDisplays) {
            if (display.isValid()) {
                display.setTeleportDuration(1);
                display.teleport(currentLoc);
            }
        }
        lastLocation = currentLoc.clone();
    }

    public void respawnBones() {
        removeAll();
        spawnBones();
        lastLocation = null;
    }

    public void removeAll() {
        for (ItemDisplay display : spawnedDisplays) {
            if (display.isValid()) {
                display.remove();
            }
        }
        spawnedDisplays.clear();
        for (BoneNode rootBone : modelStructure.getRootBones()) {
            clearDisplaysRecursive(rootBone);
        }
    }
    // #endregion

    // #region [Private Methods]
    private void clearDisplaysRecursive(BoneNode node) {
        node.setDisplayEntity(null);
        for (BoneNode child : node.getChildren()) {
            clearDisplaysRecursive(child);
        }
    }

    private void spawnBoneRecursive(BoneNode node, Location location) {
        if (node.hasMesh()) {
            ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, entity -> {
                ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    if (node.getCustomModelData() > 0) {
                        meta.setCustomModelData(node.getCustomModelData());
                    }
                    try {
                        org.bukkit.NamespacedKey modelKey = org.bukkit.NamespacedKey.fromString("exea:void_overseer/" + node.getName().toLowerCase());
                        if (modelKey != null) {
                            meta.setItemModel(modelKey);
                        }
                    } catch (Throwable ignored) {
                    }
                    item.setItemMeta(meta);
                }
                entity.setItemStack(item);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setDisplayWidth(2.0f);
                entity.setDisplayHeight(2.0f);
                entity.setViewRange(64.0f);
                entity.setShadowRadius(0.0f);
                entity.setTeleportDuration(1);
                entity.setPersistent(true);
                entity.addScoreboardTag("exea_bone");

                Vector3f pivotOffset = new Vector3f(node.getPivot()).mul(1.0f / 16.0f);
                Quaternionf initialRot = new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(node.getDefaultRotation().x),
                        (float) Math.toRadians(node.getDefaultRotation().y),
                        (float) Math.toRadians(node.getDefaultRotation().z)
                );

                Transformation initialTransform = new Transformation(
                        pivotOffset,
                        initialRot,
                        new Vector3f(1.0f, 1.0f, 1.0f),
                        new Quaternionf()
                );
                entity.setTransformation(initialTransform);
            });

            node.setDisplayEntity(display);
            spawnedDisplays.add(display);
        }

        for (BoneNode child : node.getChildren()) {
            spawnBoneRecursive(child, location);
        }
    }
    // #endregion
}
