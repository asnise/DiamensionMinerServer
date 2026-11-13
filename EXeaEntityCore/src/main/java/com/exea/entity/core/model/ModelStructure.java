package com.exea.entity.core.model;

import org.joml.Vector3f;

import java.util.*;

public class ModelStructure {

    // #region [Variables/Fields]
    private final String id;
    private final List<BoneNode> rootBones;
    private final Map<String, BoneNode> boneMap = new HashMap<>();
    // #endregion

    // #region [Properties]
    public String getId() {
        return id;
    }

    public List<BoneNode> getRootBones() {
        return Collections.unmodifiableList(rootBones);
    }

    public java.util.Collection<BoneNode> getAllBones() {
        return Collections.unmodifiableCollection(boneMap.values());
    }

    public BoneNode getBone(String name) {
        return boneMap.get(name);
    }
    // #endregion

    // #region [Public Methods]
    public ModelStructure(String id, List<BoneNode> rootBones) {
        this.id = id;
        this.rootBones = rootBones;
        for (BoneNode root : rootBones) {
            registerBoneRecursive(root);
        }
    }

    public ModelStructure instantiate() {
        List<BoneNode> clonedRoots = new ArrayList<>();
        for (BoneNode root : rootBones) {
            clonedRoots.add(cloneNodeRecursive(root));
        }
        return new ModelStructure(id, clonedRoots);
    }
    // #endregion

    // #region [Private Methods]
    private void registerBoneRecursive(BoneNode node) {
        boneMap.put(node.getName(), node);
        for (BoneNode child : node.getChildren()) {
            registerBoneRecursive(child);
        }
    }

    private BoneNode cloneNodeRecursive(BoneNode source) {
        BoneNode clone = new BoneNode(
                source.getName(),
                new Vector3f(source.getPivot()),
                new Vector3f(source.getDefaultRotation()),
                source.getCustomModelData()
        );
        clone.setHasMesh(source.hasMesh());
        clone.setMeshScale(source.getMeshScale());
        clone.setGlow(source.isGlow());
        for (BoneNode child : source.getChildren()) {
            clone.addChild(cloneNodeRecursive(child));
        }
        return clone;
    }
    // #endregion
}
