package com.exea.entity.core.effect;

import com.google.gson.JsonObject;

import java.util.Random;

/**
 * A shape in the local frame of an anchor (docs/FORMATS.md §1.2): x = right, y = up, z = forward,
 * in blocks. Flat shapes lie on the y = 0 plane. Used by particle and telegraph layers, and by
 * skill hit areas (Task 13.4).
 */
public final class Shape {

    public enum Type { POINT, SPHERE, CIRCLE, RING, CONE, LINE, BOX, COLUMN, SPIRAL }

    public final Type type;
    final double radius, radiusEnd, width, angle, length, lengthEnd, height, heightEnd, turns;
    final double[] size;
    final boolean hollow;
    final int arms;
    /** Line only: runs to the target instead of forward. */
    public final boolean toTarget;

    private Shape(Type type, JsonObject j) {
        this.type = type;
        radius = Json.num(j, "radius", type == Type.CONE || type == Type.CIRCLE ? 3 : 1);
        radiusEnd = Json.num(j, "radius_end", radius);
        width = Json.num(j, "width", type == Type.RING ? 0.2 : 0);
        angle = Json.num(j, "angle", 90);
        length = Json.num(j, "length", 3);
        lengthEnd = Json.num(j, "length_end", length);
        height = Json.num(j, "height", type == Type.SPIRAL || type == Type.COLUMN ? 2 : 0);
        heightEnd = Json.num(j, "height_end", height);
        size = Json.vec3(j, "size", new double[]{1, 1, 1});
        hollow = Json.bool(j, "hollow", false);
        turns = Json.num(j, "turns", 1);
        arms = Math.max(1, (int) Json.num(j, "arms", 1));
        toTarget = "target".equals(Json.str(j, "to", "forward"));
    }

    public static final Shape POINT = new Shape(Type.POINT, new JsonObject());

    /** Null JSON = a point. Unknown type = null (the caller warns). */
    public static Shape parse(JsonObject j) {
        if (j == null) return POINT;
        try {
            return new Shape(Type.valueOf(Json.str(j, "type", "point").toUpperCase()), j);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public double radius(double p, double scale) { return lerp(radius, radiusEnd, p) * scale; }
    public double length(double p, double scale) { return lerp(length, lengthEnd, p) * scale; }
    public double height(double p, double scale) { return lerp(height, heightEnd, p) * scale; }
    public double angle() { return angle; }

    private static double lerp(double a, double b, double t) { return a + (b - a) * Math.max(0, Math.min(1, t)); }

    /**
     * One random point of the shape into {@code out} (local frame). {@code progress} 0..1 drives the
     * {@code *_end} values; {@code lineLength} overrides the line length (a line to the target).
     */
    public void sample(Random r, double progress, double scale, double lineLength, double[] out) {
        double rad = radius(progress, scale);
        switch (type) {
            case POINT -> set(out, 0, 0, 0);
            case SPHERE -> {
                double u = r.nextDouble() * 2 - 1, phi = r.nextDouble() * Math.PI * 2;
                double d = hollow ? rad : rad * Math.cbrt(r.nextDouble());
                double s = Math.sqrt(1 - u * u);
                set(out, d * s * Math.cos(phi), d * u, d * s * Math.sin(phi));
            }
            case CIRCLE -> {
                double a = r.nextDouble() * Math.PI * 2, d = rad * Math.sqrt(r.nextDouble());
                set(out, d * Math.cos(a), 0, d * Math.sin(a));
            }
            case RING -> {
                double a = r.nextDouble() * Math.PI * 2, d = rad + (r.nextDouble() - 0.5) * width * scale;
                set(out, d * Math.cos(a), 0, d * Math.sin(a));
            }
            case CONE -> {
                double a = Math.toRadians((r.nextDouble() - 0.5) * angle), d = rad * Math.sqrt(r.nextDouble());
                set(out, d * Math.sin(a), 0, d * Math.cos(a));
            }
            case LINE -> {
                double len = lineLength >= 0 ? lineLength : length(progress, scale);
                set(out, (r.nextDouble() - 0.5) * width * scale, 0, r.nextDouble() * len);
            }
            case BOX -> set(out, (r.nextDouble() - 0.5) * size[0] * scale, (r.nextDouble() - 0.5) * size[1] * scale,
                    (r.nextDouble() - 0.5) * size[2] * scale);
            case COLUMN -> {
                double a = r.nextDouble() * Math.PI * 2, d = hollow ? rad : rad * Math.sqrt(r.nextDouble());
                set(out, d * Math.cos(a), r.nextDouble() * height(progress, scale), d * Math.sin(a));
            }
            case SPIRAL -> {
                double t = r.nextDouble();
                int arm = r.nextInt(arms);
                double a = t * turns * Math.PI * 2 + arm * Math.PI * 2 / arms;
                set(out, rad * Math.cos(a), t * height(progress, scale), rad * Math.sin(a));
            }
        }
    }

    /** Point {@code i} of {@code n} evenly spaced along the outline (for telegraphs), local frame. */
    public void outline(int i, int n, double progress, double scale, double[] out) {
        double t = (double) i / n;
        double rad = radius(progress, scale);
        switch (type) {
            case CIRCLE, RING, SPHERE, COLUMN, SPIRAL, POINT -> set(out, rad * Math.cos(t * Math.PI * 2), 0, rad * Math.sin(t * Math.PI * 2));
            case CONE -> {
                // arc, then the two straight edges back to the apex
                double half = Math.toRadians(angle / 2), arc = Math.toRadians(angle) * rad, edges = 2 * rad, total = arc + edges;
                double d = t * total;
                if (d < arc) {
                    double a = -half + d / rad;
                    set(out, rad * Math.sin(a), 0, rad * Math.cos(a));
                } else if (d < arc + rad) {
                    double k = 1 - (d - arc) / rad;
                    set(out, rad * k * Math.sin(half), 0, rad * k * Math.cos(half));
                } else {
                    double k = (d - arc - rad) / rad;
                    set(out, -rad * k * Math.sin(half), 0, rad * k * Math.cos(half));
                }
            }
            case LINE -> {
                double len = length(progress, scale), w = Math.max(0.3, width * scale) / 2, total = 2 * len + 4 * w, d = t * total;
                if (d < len) set(out, -w, 0, d);
                else if (d < len + 2 * w) set(out, -w + (d - len), 0, len);
                else if (d < 2 * len + 2 * w) set(out, w, 0, len - (d - len - 2 * w));
                else set(out, w - (d - 2 * len - 2 * w), 0, 0);
            }
            case BOX -> {
                double hx = size[0] * scale / 2, hz = size[2] * scale / 2, total = 4 * (hx + hz), d = t * total;
                if (d < 2 * hx) set(out, -hx + d, 0, -hz);
                else if (d < 2 * hx + 2 * hz) set(out, hx, 0, -hz + (d - 2 * hx));
                else if (d < 4 * hx + 2 * hz) set(out, hx - (d - 2 * hx - 2 * hz), 0, hz);
                else set(out, -hx, 0, hz - (d - 4 * hx - 2 * hz));
            }
        }
    }

    /** Outline length in blocks, to space outline points evenly. */
    public double perimeter(double progress, double scale) {
        double rad = radius(progress, scale);
        return switch (type) {
            case CONE -> Math.toRadians(angle) * rad + 2 * rad;
            case LINE -> 2 * length(progress, scale) + 2 * Math.max(0.3, width * scale);
            case BOX -> 2 * (size[0] + size[2]) * scale;
            default -> Math.PI * 2 * rad;
        };
    }

    /** Is a local point inside the shape? Flat shapes ignore y unless it is beyond {@code flatHeight}. */
    public boolean contains(double x, double y, double z, double progress, double scale, double flatHeight) {
        double rad = radius(progress, scale);
        double flat = Math.hypot(x, z);
        boolean inHeight = Math.abs(y) <= flatHeight;
        return switch (type) {
            case POINT -> x * x + y * y + z * z <= 0.25 * scale * scale;
            case SPHERE -> x * x + y * y + z * z <= rad * rad;
            case CIRCLE -> inHeight && flat <= rad;
            case RING -> inHeight && Math.abs(flat - rad) <= Math.max(0.5, width * scale);
            case CONE -> inHeight && flat <= rad && (flat < 1e-6 || Math.abs(Math.toDegrees(Math.atan2(x, z))) <= angle / 2);
            case LINE -> inHeight && z >= 0 && z <= length(progress, scale) && Math.abs(x) <= Math.max(0.5, width * scale / 2);
            case BOX -> Math.abs(x) <= size[0] * scale / 2 && Math.abs(y) <= size[1] * scale / 2 && Math.abs(z) <= size[2] * scale / 2;
            case COLUMN, SPIRAL -> flat <= rad && y >= -0.5 && y <= height(progress, scale);
        };
    }

    private static void set(double[] o, double x, double y, double z) {
        o[0] = x;
        o[1] = y;
        o[2] = z;
    }
}
