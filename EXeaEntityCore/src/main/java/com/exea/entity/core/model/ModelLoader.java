package com.exea.entity.core.model;

import com.exea.entity.core.animation.KeyframeTimeline;
import com.exea.entity.core.timeline.TimelineEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

                if (obj.has("mesh_scale")) {
                    node.setMeshScale(obj.get("mesh_scale").getAsFloat());
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
                            KeyframeTimeline.Interpolation interp = KeyframeTimeline.Interpolation.parse(
                                    kf.has("interpolation") ? kf.get("interpolation").getAsString() : null);
                            Vector3f pre = vec(kf.getAsJsonArray("vector"));
                            Vector3f post = kf.has("post") ? vec(kf.getAsJsonArray("post")) : null;

                            // Values are in Blockbench scene space (= Minecraft model space): Blockbench 5
                            // adds them straight onto the bone (BoneAnimator.displayPosition/Rotation use
                            // +=, no sign flips, verified in 5.2.2). Position is in pixels.
                            switch (channel) {
                                case "position" -> {
                                    toScene(pre, 1f / 16f, 1f / 16f, 1f / 16f);
                                    if (post != null) toScene(post, 1f / 16f, 1f / 16f, 1f / 16f);
                                    timeline.addPositionKeyframe(boneName, new KeyframeTimeline.Keyframe(time, pre, post, interp));
                                }
                                case "rotation" -> timeline.addRotationKeyframe(boneName, new KeyframeTimeline.Keyframe(time, pre, post, interp));
                                case "scale" -> timeline.addScaleKeyframe(boneName, new KeyframeTimeline.Keyframe(time, pre, post, interp));
                            }
                        }
                    }
                }

                // Blockbench effect keyframes (sound / particle / timeline script), see TimelineEvent.
                if (animObj.has("events")) {
                    for (JsonElement evEl : animObj.getAsJsonArray("events")) {
                        JsonObject ev = evEl.getAsJsonObject();
                        float time = ev.has("time") ? ev.get("time").getAsFloat() : 0f;
                        String type = ev.has("type") ? ev.get("type").getAsString().toLowerCase() : "";
                        String bone = ev.has("bone") && !ev.get("bone").isJsonNull() ? ev.get("bone").getAsString() : null;
                        switch (type) {
                            case "sound" -> {
                                if (ev.has("sound") && !ev.get("sound").getAsString().isBlank()) {
                                    timeline.addEvent(TimelineEvent.sound(time, ev.get("sound").getAsString(), bone));
                                }
                            }
                            case "particle" -> {
                                TimelineEvent p = ev.has("particle") ? TimelineEvent.particle(time, ev.get("particle").getAsString(), bone) : null;
                                if (p != null) timeline.addEvent(p);
                            }
                            case "script" -> {
                                String script = ev.has("script") ? ev.get("script").getAsString() : "";
                                for (String line : script.split("[\\r\\n;]+")) {
                                    TimelineEvent s = TimelineEvent.script(time, line);
                                    if (s != null) timeline.addEvent(s);
                                }
                            }
                            default -> { }
                        }
                    }
                }

                anims.put(animName, timeline);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return anims;
    }
    // #endregion

    // #region [Private Methods]
    private static Vector3f vec(JsonArray arr) {
        return new Vector3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
    }

    private static void toScene(Vector3f v, float sx, float sy, float sz) {
        v.set(v.x * sx, v.y * sy, v.z * sz);
    }
    // #endregion
}
