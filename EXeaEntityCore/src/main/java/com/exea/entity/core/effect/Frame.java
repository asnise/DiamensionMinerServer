package com.exea.entity.core.effect;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

/**
 * A point plus a facing: turns local [right, up, forward] offsets into world positions. Yaw is
 * Minecraft yaw (0 = +Z, 90 = -X).
 */
public final class Frame {

    public final World world;
    public final double x, y, z;
    public final float yaw;
    final double fx, fz, rx, rz;

    public Frame(Location l, float yaw) {
        this.world = l.getWorld();
        this.x = l.getX();
        this.y = l.getY();
        this.z = l.getZ();
        this.yaw = yaw;
        double r = Math.toRadians(yaw);
        fx = -Math.sin(r);
        fz = Math.cos(r);
        rx = -Math.cos(r);
        rz = -Math.sin(r);
    }

    public Location toWorld(double right, double up, double forward) {
        return new Location(world, x + right * rx + forward * fx, y + up, z + right * rz + forward * fz, yaw, 0);
    }

    public Location toWorld(double[] local) {
        return toWorld(local[0], local[1], local[2]);
    }

    /** A local direction in world axes (not normalised). */
    public Vector dir(double right, double up, double forward) {
        return new Vector(right * rx + forward * fx, up, right * rz + forward * fz);
    }

    public Vector forward() { return new Vector(fx, 0, fz); }

    public Location location() { return new Location(world, x, y, z, yaw, 0); }

    public Frame at(Location l) { return new Frame(l, yaw); }

    /**
     * Top of the first solid block at or below {@code l} (up to {@code down} blocks), or null. A point
     * inside a solid block first moves up out of it (2 blocks at most).
     */
    public static Double groundY(World w, double x, double y, double z, int down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int by = (int) Math.floor(y);
        for (int up = 0; up < 2 && solid(w.getBlockAt(bx, by, bz)); up++) by++;
        for (int i = 0; i <= down; i++) {
            Block b = w.getBlockAt(bx, by - i, bz);
            if (solid(b)) {
                double top = b.getBoundingBox().getMaxY();
                return top > b.getY() ? top : b.getY() + 1.0;
            }
        }
        return null;
    }

    /** The real block under a point (air skipped downward), for "ground" blocks. */
    public static Block groundBlock(World w, double x, double y, double z, int down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y - 0.01);
        for (int i = 0; i <= down; i++) {
            Block b = w.getBlockAt(bx, by - i, bz);
            if (solid(b)) return b;
        }
        return null;
    }

    public static boolean solid(Block b) {
        return b.getType().isSolid() && !b.isPassable();
    }
}
