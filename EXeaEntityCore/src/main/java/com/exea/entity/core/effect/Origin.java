package com.exea.entity.core.effect;

import com.exea.entity.core.manager.EXActiveEntity;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Where an effect is played: a fixed place, an entity or a bone of an EXea entity. Remembers the last
 * good position, so layers still have a place after the entity is gone.
 */
public abstract class Origin {

    private Location last;
    private float lastYaw;

    protected abstract Location compute();
    protected abstract float computeYaw();
    public abstract boolean valid();
    /** The entity behind this origin, or null. */
    public Entity entity() { return null; }

    public final Location location() {
        if (valid()) {
            Location l = compute();
            if (l != null) {
                last = l;
                lastYaw = computeYaw();
            }
        }
        return last == null ? null : last.clone();
    }

    public final float yaw() {
        if (valid()) lastYaw = computeYaw();
        return lastYaw;
    }

    public static Origin fixed(Location loc, float yaw) {
        Location l = loc.clone();
        return new Origin() {
            protected Location compute() { return l; }
            protected float computeYaw() { return yaw; }
            public boolean valid() { return true; }
        };
    }

    /** Body yaw for living entities (models turn with the body), location yaw otherwise. */
    static float facing(Entity e) {
        return e instanceof LivingEntity le ? le.getBodyYaw() : e.getLocation().getYaw();
    }

    public static Origin entity(Entity e, Float yaw) {
        return new Origin() {
            protected Location compute() { return e.getLocation(); }
            protected float computeYaw() { return yaw != null ? yaw : facing(e); }
            public boolean valid() { return e.isValid(); }
            public Entity entity() { return e; }
        };
    }

    public static Origin bone(EXActiveEntity a, String bone, Float yaw) {
        return new Origin() {
            protected Location compute() {
                Location l = a.getBoneLocation(bone);
                return l != null ? l : a.getRootEntity().getLocation().add(0, 1, 0);
            }
            protected float computeYaw() { return yaw != null ? yaw : facing(a.getRootEntity()); }
            public boolean valid() { return a.isValid() && a.getRootEntity().isValid(); }
            public Entity entity() { return a.getRootEntity(); }
        };
    }
}
