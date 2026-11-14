package com.exea.entity.core.timeline;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Something that happens at a moment of an animation, from a Blockbench effect keyframe
 * (exported as {@code "events"} in {@code <id>_anims.json}):
 * <ul>
 *   <li>Sound keyframe: {@code {"time": 0.5, "type": "sound", "sound": "entity.wither.shoot"}}</li>
 *   <li>Particle keyframe: {@code {"time": 0.5, "type": "particle", "particle": "flame", "bone": "hand_r"}}</li>
 *   <li>Timeline keyframe, one line of its script: {@code {"time": 0.5, "type": "script", "script": "hit radius=3 damage=10"}}</li>
 * </ul>
 * Script lines understood by EXea (anything else only fires {@code EXeaAnimationEventTriggerEvent}):
 * <pre>
 * hit radius=3.5 damage=20 [cone=120] [bone=hand_r] [knockback=0.6]
 * sound &lt;key&gt; [volume=1] [pitch=1] [bone=...]
 * particle &lt;key&gt; [count=10] [spread=0.5] [speed=0] [bone=...]
 * fx &lt;effect id&gt; [bone=...] [scale=1] [follow=true] [yaw=0]
 * </pre>
 */
public final class TimelineEvent {

    public enum Type { SOUND, PARTICLE, HIT, FX, CUSTOM }

    private final float timestamp;
    private final Type type;
    /** The tag passed to EXeaAnimationEventTriggerEvent: the script line, or "sound:key" / "particle:key". */
    private final String eventTag;
    private final String bone;
    private final String soundKey;
    private final float volume;
    private final float pitch;
    private final Particle particle;
    private final int particleCount;
    private final double particleSpread;
    private final double particleSpeed;
    private final double radius;
    private final double damage;
    private final double cone;
    private final double knockback;
    /** FX: effect preset id, scale, whether it tracks the bone, extra yaw. */
    private final String effectId;
    private final double fxScale;
    private final boolean fxFollow;
    private final float fxYaw;

    private TimelineEvent(float timestamp, Type type, String eventTag, String bone, Map<String, String> a,
                          String soundKey, Particle particle) {
        this.timestamp = timestamp;
        this.type = type;
        this.eventTag = eventTag;
        this.bone = bone == null || bone.isBlank() ? null : bone.toLowerCase(Locale.ROOT);
        this.soundKey = soundKey;
        this.particle = particle;
        this.volume = (float) num(a, "volume", 1);
        this.pitch = (float) num(a, "pitch", 1);
        this.particleCount = (int) num(a, "count", 10);
        this.particleSpread = num(a, "spread", 0.3);
        this.particleSpeed = num(a, "speed", 0);
        this.radius = num(a, "radius", 3);
        this.damage = num(a, "damage", 0);
        this.cone = num(a, "cone", 360);
        this.knockback = num(a, "knockback", 0);
        this.effectId = type == Type.FX ? soundKey : null;
        this.fxScale = num(a, "scale", 1);
        this.fxFollow = !"false".equalsIgnoreCase(a.get("follow"));
        this.fxYaw = (float) num(a, "yaw", 0);
    }

    /** Sound keyframe ({@code effect} = Java sound key, e.g. entity.wither.shoot). */
    public static TimelineEvent sound(float time, String key, String bone) {
        return new TimelineEvent(time, Type.SOUND, "sound:" + key, bone, Map.of(), key, null);
    }

    /** Particle keyframe ({@code effect} = particle key, e.g. flame). Null if the particle is unknown. */
    public static TimelineEvent particle(float time, String key, String bone) {
        Particle p = particleOf(key);
        return p == null ? null : new TimelineEvent(time, Type.PARTICLE, "particle:" + key, bone, Map.of(), null, p);
    }

    /** One line of a timeline keyframe script. Null for a blank line. */
    public static TimelineEvent script(float time, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String[] words = trimmed.split("\\s+");
        String verb = words[0].toLowerCase(Locale.ROOT);
        Map<String, String> args = new HashMap<>();
        String positional = null;
        for (int i = 1; i < words.length; i++) {
            int eq = words[i].indexOf('=');
            if (eq > 0) args.put(words[i].substring(0, eq).toLowerCase(Locale.ROOT), words[i].substring(eq + 1));
            else if (positional == null) positional = words[i];
        }
        String bone = args.get("bone");
        return switch (verb) {
            case "hit" -> new TimelineEvent(time, Type.HIT, trimmed, bone, args, null, null);
            case "sound" -> positional == null ? custom(time, trimmed)
                    : new TimelineEvent(time, Type.SOUND, trimmed, bone, args, positional, null);
            case "particle" -> {
                Particle p = positional == null ? null : particleOf(positional);
                yield p == null ? custom(time, trimmed) : new TimelineEvent(time, Type.PARTICLE, trimmed, bone, args, null, p);
            }
            // The effect id travels in the sound-key slot; see effectId in the constructor.
            case "fx" -> positional == null ? custom(time, trimmed)
                    : new TimelineEvent(time, Type.FX, trimmed, bone, args, positional.toLowerCase(Locale.ROOT), null);
            default -> custom(time, trimmed);
        };
    }

    private static TimelineEvent custom(float time, String line) {
        return new TimelineEvent(time, Type.CUSTOM, line, null, Map.of(), null, null);
    }

    private static Particle particleOf(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        NamespacedKey nk = NamespacedKey.fromString(k.contains(":") ? k : "minecraft:" + k);
        return nk == null ? null : Registry.PARTICLE_TYPE.get(nk);
    }

    private static double num(Map<String, String> a, String key, double fallback) {
        String v = a.get(key);
        if (v == null) return fallback;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public float getTimestamp() { return timestamp; }
    public Type getType() { return type; }
    public String getEventTag() { return eventTag; }
    /** Bone the event happens at, or null for the driver's position (1 block up). */
    public String getBone() { return bone; }
    public String getSoundKey() { return soundKey; }
    public float getSoundVolume() { return volume; }
    public float getSoundPitch() { return pitch; }
    public Particle getParticle() { return particle; }
    public int getParticleCount() { return particleCount; }
    public double getParticleSpread() { return particleSpread; }
    public double getParticleSpeed() { return particleSpeed; }
    /** HIT: blocks around the bone (or driver). */
    public double getRadius() { return radius; }
    public double getDamage() { return damage; }
    /** HIT: full angle in front of the body, degrees; 360 = all around. */
    public double getCone() { return cone; }
    public double getKnockback() { return knockback; }
    /** FX: the effect preset id. */
    public String getEffectId() { return effectId; }
    public double getFxScale() { return fxScale; }
    public boolean isFxFollow() { return fxFollow; }
    public float getFxYaw() { return fxYaw; }
}
