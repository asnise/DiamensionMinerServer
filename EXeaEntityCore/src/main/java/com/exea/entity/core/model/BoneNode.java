package com.exea.entity.core.model;

import org.bukkit.entity.ItemDisplay;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class BoneNode {

    // #region [Variables/Fields]
    private final String name;
    private final Vector3f pivot;
    private final Vector3f defaultRotation;
    private final int customModelData;
    private BoneNode parent;
    private final List<BoneNode> children = new ArrayList<>();
    private ItemDisplay displayEntity;
    private boolean hasMesh = true;
    /** The exporter shrinks meshes that exceed the item-model -16..32 box by this factor; the renderer scales back up. */
    private float meshScale = 1f;
    /** Drawn at full brightness (block + sky light 15), for glowing parts. */
    private boolean glow = false;
    // #endregion

    // #region [Properties]
    public boolean hasMesh() {
        return hasMesh;
    }

    public void setHasMesh(boolean hasMesh) {
        this.hasMesh = hasMesh;
    }

    public boolean isGlow() {
        return glow;
    }

    public void setGlow(boolean glow) {
        this.glow = glow;
    }

    public float getMeshScale() {
        return meshScale;
    }

    public void setMeshScale(float meshScale) {
        this.meshScale = meshScale > 0f ? meshScale : 1f;
    }

    public String getName() {
        return name;
    }

    public Vector3f getPivot() {
        return pivot;
    }

    public Vector3f getDefaultRotation() {
        return defaultRotation;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public BoneNode getParent() {
        return parent;
    }

    public void setParent(BoneNode parent) {
        this.parent = parent;
    }

    public List<BoneNode> getChildren() {
        return children;
    }

    public ItemDisplay getDisplayEntity() {
        return displayEntity;
    }

    public void setDisplayEntity(ItemDisplay displayEntity) {
        this.displayEntity = displayEntity;
    }
    // #endregion

    // #region [Public Methods]
    public BoneNode(String name, Vector3f pivot, Vector3f defaultRotation, int customModelData) {
        this.name = name;
        this.pivot = pivot;
        this.defaultRotation = defaultRotation;
        this.customModelData = customModelData;
    }

    public void addChild(BoneNode child) {
        child.setParent(this);
        children.add(child);
    }
    // #endregion
}
