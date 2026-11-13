package com.exea.entity.core.render;

import com.exea.entity.core.EXeaStats;
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

/**
 * Spawns and moves the bone displays of one entity.
 *
 * <p>All bones ride one invisible carrier display that follows the driver. Moving the model is then
 * one teleport (the carrier) instead of one per bone, and the client moves the riding bones with it.
 * Turning is not a teleport at all: the body yaw is part of each bone's transform
 * ({@code AnimationController}). The driver itself carries nothing, so it stays a normal mob: other
 * plugins can teleport it anywhere (also to another world) or eject its riders without breaking the model.
 */
public class DisplayRenderer {

    // #region [Variables/Fields]
    private final LivingEntity rootEntity;
    private final ModelStructure modelStructure;
    private final List<ItemDisplay> spawnedDisplays = new ArrayList<>();
    private ItemDisplay carrier;
    private Location lastLocation = null;
    private float riderOffset = 0f;
    private Runnable onRespawn;
    // #endregion

    // #region [Properties]
    public List<ItemDisplay> getSpawnedDisplays() {
        return spawnedDisplays;
    }

    /** How far above the carrier the riding bones sit (0 for display carriers; measured to be sure). */
    public float getRiderOffset() {
        return riderOffset;
    }

    /** Called after the bones were (re)spawned, so the animation resends every pose. */
    public void setOnRespawn(Runnable onRespawn) {
        this.onRespawn = onRespawn;
    }

    /** True if at least one player's client is tracking the model (and so would see pose updates). */
    public boolean isWatched() {
        if (carrier != null && carrier.isValid() && !carrier.getTrackedBy().isEmpty()) return true;
        return !spawnedDisplays.isEmpty() && spawnedDisplays.get(0).isValid() && !spawnedDisplays.get(0).getTrackedBy().isEmpty();
    }
    // #endregion

    // #region [Public Methods]
    public DisplayRenderer(LivingEntity rootEntity, ModelStructure modelStructure) {
        this.rootEntity = rootEntity;
        this.modelStructure = modelStructure;
    }

    public void spawnBones() {
        Location base = carrierLocation();
        carrier = base.getWorld().spawn(base, ItemDisplay.class, entity -> {
            // No item: nothing is drawn, it only carries the bones.
            entity.setPersistent(false);
            entity.setTeleportDuration(1);
            entity.setViewRange(64.0f);
            entity.addScoreboardTag("exea_bone");
        });
        for (BoneNode rootBone : modelStructure.getRootBones()) {
            spawnBoneRecursive(rootBone, base);
        }
        for (ItemDisplay display : spawnedDisplays) {
            carrier.addPassenger(display);
        }
        lastLocation = base.clone();
        if (onRespawn != null) onRespawn.run();
    }

    /**
     * Follows the driver: one carrier teleport when it moved, nothing when it only turned. Respawns
     * everything if a display was killed, and re-mounts a bone that fell off.
     */
    public void updatePositions() {
        if (!rootEntity.isValid() || rootEntity.isDead()) {
            return;
        }

        if (carrier == null || !carrier.isValid()) {
            respawnBones();
            return;
        }
        for (ItemDisplay display : spawnedDisplays) {
            if (!display.isValid() || display.isDead()) {
                respawnBones();
                return;
            }
        }

        Location currentLoc = carrierLocation();
        if (lastLocation != null && currentLoc.getWorld() != lastLocation.getWorld()) {
            // Passengers cannot follow a teleport into another world.
            respawnBones();
            return;
        }
        if (lastLocation == null || currentLoc.distanceSquared(lastLocation) > 0.0001) {
            carrier.teleport(currentLoc); // passengers stay on (Paper 1.21.10+ default)
            lastLocation = currentLoc;
            EXeaStats.carrierMoves++;
        }

        ItemDisplay first = null;
        for (ItemDisplay display : spawnedDisplays) {
            if (display.getVehicle() != carrier) {
                carrier.addPassenger(display);
            } else if (first == null) {
                first = display;
            }
        }
        if (first != null) {
            riderOffset = (float) (first.getLocation().getY() - carrier.getLocation().getY());
        }
    }

    /** After a player joins or respawns: make sure every bone rides and the carrier is in place. */
    public void forceSync() {
        if (!rootEntity.isValid() || rootEntity.isDead()) {
            return;
        }
        lastLocation = null;
        updatePositions();
    }

    /** The carrier stands at the driver's feet facing yaw 0 (the yaw is in the bone transforms). */
    private Location carrierLocation() {
        Location loc = rootEntity.getLocation();
        loc.setPitch(0f);
        loc.setYaw(0f);
        return loc;
    }

    public void respawnBones() {
        removeAll();
        EXeaStats.respawns++;
        spawnBones();
    }

    public void removeAll() {
        for (ItemDisplay display : spawnedDisplays) {
            if (display.isValid()) {
                display.remove();
            }
        }
        spawnedDisplays.clear();
        if (carrier != null && carrier.isValid()) {
            carrier.remove();
        }
        carrier = null;
        lastLocation = null;
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
