package com.exea.entity.core.render;

import com.exea.entity.core.model.BoneNode;
import com.exea.entity.core.model.ModelStructure;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
        Location baseLocation = bodyLocation();

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

        Location currentLoc = bodyLocation();
        if (lastLocation == null ||
                currentLoc.getWorld() != lastLocation.getWorld() ||
                currentLoc.distanceSquared(lastLocation) > 0.0001 ||
                Math.abs(currentLoc.getYaw() - lastLocation.getYaw()) > 0.05f) {

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

        Location currentLoc = bodyLocation();
        for (ItemDisplay display : spawnedDisplays) {
            if (display.isValid()) {
                display.setTeleportDuration(1);
                display.teleport(currentLoc);
            }
        }
        lastLocation = currentLoc.clone();
    }

    /**
     * Where the model is drawn: the root's position and body yaw, with pitch forced to 0.
     * The root's location pitch is where its head looks, which used to tilt the whole model.
     */
    private Location bodyLocation() {
        Location loc = rootEntity.getLocation();
        loc.setPitch(0f);
        // A mob's location yaw is where its head points; the model follows the body like vanilla mobs.
        loc.setYaw(rootEntity.getBodyYaw());
        return loc;
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
                        org.bukkit.NamespacedKey modelKey = org.bukkit.NamespacedKey.fromString("exea:" + modelStructure.getId() + "/" + node.getName().toLowerCase());
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
                // Culling box around the entity position: bones sit up to a few blocks from the root.
                entity.setDisplayWidth(6.0f);
                entity.setDisplayHeight(6.0f);
                entity.setViewRange(64.0f);
                entity.setShadowRadius(0.0f);
                if (node.isGlow()) {
                    entity.setBrightness(new Display.Brightness(15, 15));
                }
                entity.setTeleportDuration(1);
                // Never saved to disk, same as the root mob: after a crash or chunk unload the
                // bones disappear with the root instead of being left behind as frozen statues.
                entity.setPersistent(false);
                entity.addScoreboardTag("exea_bone");

                // The real pose is sent by AnimationController on the same tick (snapping, no interpolation).
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
