package com.exea.entity.api.effect;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** What a plugin-registered layer type gets each time it runs. */
public interface EffectLayerContext {

    /** Id of the effect preset this layer belongs to. */
    String getEffectId();

    /** The layer's anchor: origin + offset (+ ground snap), facing included as the location's yaw. */
    Location getLocation();

    /** Facing in Minecraft yaw. */
    float getYaw();

    /** The play's scale (already applied to {@link #getLocation()}'s offset). */
    double getScale();

    /** The play's target, or null. */
    Entity getTarget();

    /** The entity the effect plays on, or null for a plain location. */
    Entity getSource();

    /** The layer's JSON as written in the file (read your own keys from it). Do not modify. */
    JsonObject getJson();

    /** 0..1 through the layer's duration (1 for a burst). */
    double getProgress();

    /** Ticks since the layer started. */
    int getTick();
}
