package com.exea.entity.api.effect;

import org.bukkit.entity.Entity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Settings for one play of an effect preset. Everything is optional:
 * <pre>{@code
 * exea.playEffect("ground_slam", loc, EffectOptions.create().scale(1.5).yaw(player.getYaw()).target(player));
 * }</pre>
 */
public final class EffectOptions {

    private double scale = 1;
    private Float yaw;
    private Entity target;
    private Boolean follow;
    private final Set<String> tags = new LinkedHashSet<>();

    public static EffectOptions create() {
        return new EffectOptions();
    }

    /** Multiplies sizes, radii, distances and speeds (not counts or times). Default 1. */
    public EffectOptions scale(double scale) {
        this.scale = scale > 0 ? scale : 1;
        return this;
    }

    /** Facing in Minecraft yaw for shapes and directions. Default: the origin entity's body yaw, or 0 at a location. */
    public EffectOptions yaw(float yaw) {
        this.yaw = yaw;
        return this;
    }

    /** Target for layers anchored {@code "at": "target"} and lines/beams {@code "to": "target"}. */
    public EffectOptions target(Entity target) {
        this.target = target;
        return this;
    }

    /**
     * When playing on an entity or a bone: true = layers with {@code "follow": true} track it while
     * they run (the default); false = the effect stays where it started.
     */
    public EffectOptions follow(boolean follow) {
        this.follow = follow;
        return this;
    }

    /** Tags that enable layers with a matching {@code "when"}. */
    public EffectOptions tag(String... tags) {
        Collections.addAll(this.tags, tags);
        return this;
    }

    public double getScale() { return scale; }
    public Float getYaw() { return yaw; }
    public Entity getTarget() { return target; }
    public boolean isFollow() { return follow == null || follow; }
    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
}
