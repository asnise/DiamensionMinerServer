package com.exea.entity.core.model;

import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.timeline.TimelineEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ModelLoader {

    // #region [Public Methods]
    public static ModelStructure loadModel(String modelId, InputStream stream) {
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();

            Map<String, BoneNode> nodeMap = new LinkedHashMap<>();
            Map<String, String> parentMap = new HashMap<>();

            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString().toLowerCase();
                String parent = obj.has("parent") && !obj.get("parent").isJsonNull() ? obj.get("parent").getAsString().toLowerCase() : null;

                JsonArray origArr = obj.getAsJsonArray("origin");
                Vector3f origin = new Vector3f(
                        origArr.get(0).getAsFloat(),
                        origArr.get(1).getAsFloat(),
                        origArr.get(2).getAsFloat()
                );

                JsonArray rotArr = obj.getAsJsonArray("rotation");
                Vector3f rotation = new Vector3f(
                        rotArr.get(0).getAsFloat(),
                        rotArr.get(1).getAsFloat(),
                        rotArr.get(2).getAsFloat()
                );

                int cmd = obj.get("custom_model_data").getAsInt();
                int elements = obj.get("element_count").getAsInt();

                BoneNode node = new BoneNode(name, origin, rotation, cmd);

                boolean visible = true;
                if (obj.has("visible")) {
                    visible = obj.get("visible").getAsBoolean();
                }

                if (!visible || elements == 0) {
                    node.setHasMesh(false);
                }

                nodeMap.put(name, node);
                if (parent != null) {
                    parentMap.put(name, parent);
                }
            }

            List<BoneNode> rootBones = new ArrayList<>();
            for (Map.Entry<String, BoneNode> entry : nodeMap.entrySet()) {
                String name = entry.getKey();
                BoneNode node = entry.getValue();
                String parentName = parentMap.get(name);

                if (parentName != null && nodeMap.containsKey(parentName)) {
                    nodeMap.get(parentName).addChild(node);
                } else {
                    rootBones.add(node);
                }
            }

            return new ModelStructure(modelId, rootBones);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Map<String, KeyframeTimeline> loadAnimations(InputStream stream) {
        Map<String, KeyframeTimeline> anims = new HashMap<>();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String animName = entry.getKey().toLowerCase();
                JsonObject animObj = entry.getValue().getAsJsonObject();

                float length = animObj.get("length").getAsFloat();
                String loopStr = animObj.get("loop").getAsString().toUpperCase();
                KeyframeTimeline.LoopMode loopMode;
                try {
                    loopMode = KeyframeTimeline.LoopMode.valueOf(loopStr);
                } catch (Exception ex) {
                    loopMode = loopStr.contains("LOOP") ? KeyframeTimeline.LoopMode.LOOP : KeyframeTimeline.LoopMode.ONCE;
                }

                KeyframeTimeline timeline = new KeyframeTimeline(animName, length, loopMode);

                if (animObj.has("bones")) {
                    JsonObject bonesObj = animObj.getAsJsonObject("bones");
                    for (Map.Entry<String, JsonElement> boneEntry : bonesObj.entrySet()) {
                        String boneName = boneEntry.getKey().toLowerCase();
                        JsonArray kfArray = boneEntry.getValue().getAsJsonArray();

                        for (JsonElement kfEl : kfArray) {
                            JsonObject kf = kfEl.getAsJsonObject();
                            String channel = kf.get("channel").getAsString().toLowerCase();
                            float time = kf.get("time").getAsFloat();
                            JsonArray vecArr = kf.getAsJsonArray("vector");
                            float x = vecArr.get(0).getAsFloat();
                            float y = vecArr.get(1).getAsFloat();
                            float z = vecArr.get(2).getAsFloat();

                            switch (channel) {
                                case "position" -> {
                                    Vector3f posInBlocks = new Vector3f(x / 16.0f, y / 16.0f, z / 16.0f);
                                    timeline.addPositionKeyframe(boneName, time, posInBlocks);
                                }
                                case "rotation" -> {
                                    Quaternionf rotQuat = new Quaternionf().rotationXYZ(
                                            (float) Math.toRadians(x),
                                            (float) Math.toRadians(y),
                                            (float) Math.toRadians(z)
                                    );
                                    timeline.addRotationKeyframe(boneName, time, rotQuat);
                                }
                                case "scale" -> {
                                    timeline.addScaleKeyframe(boneName, time, new Vector3f(x, y, z));
                                }
                            }
                        }
                    }
                }

                if (animName.contains("slash")) {
                    TimelineEvent slashEvent = new TimelineEvent(0.52f, "slash_impact");
                    slashEvent.setSound(Sound.ENTITY_PLAYER_ATTACK_SWEEP);
                    slashEvent.setParticle(Particle.SWEEP_ATTACK);
                    slashEvent.setHitboxRange(3.5);
                    slashEvent.setHitboxDamage(250.0);
                    timeline.addEvent(slashEvent);
                }

                anims.put(animName, timeline);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return anims;
    }
    // #endregion
}
