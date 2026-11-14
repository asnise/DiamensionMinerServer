package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/** A particle type plus the extra data it needs (dust colour, block, item), from §0 "Particles". */
final class ParticleData {

    final Particle particle;
    private final Class<?> type;
    private final Color color, toColor;
    private final float size;
    private final List<String> blocks;
    private final ItemStack item;

    private ParticleData(Particle particle, Class<?> type, Color color, Color toColor, float size, List<String> blocks, ItemStack item) {
        this.particle = particle;
        this.type = type;
        this.color = color;
        this.toColor = toColor;
        this.size = size;
        this.blocks = blocks;
        this.item = item;
    }

    /** Null (after a warning) for an unknown particle or one whose data EXea can't build. */
    static ParticleData parse(JsonObject j, String key, Consumer<String> warn) {
        String name = Json.str(j, key, null);
        if (name == null) {
            warn.accept("\"" + key + "\" is required");
            return null;
        }
        NamespacedKey nk = NamespacedKey.fromString(Json.key(name));
        Particle p = nk == null ? null : Registry.PARTICLE_TYPE.get(nk);
        if (p == null) {
            warn.accept("unknown particle \"" + name + "\"");
            return null;
        }
        Class<?> t = p.getDataType();
        Color color = Json.color(j, "color");
        Color to = Json.color(j, "to_color");
        float size = (float) Json.num(j, "size", 1);
        List<String> blocks = Json.strings(j, "block", null);
        ItemStack item = null;
        if (t == ItemStack.class) {
            Material m = Material.matchMaterial(Json.str(j, "item", "stone"));
            item = new ItemStack(m != null && m.isItem() ? m : Material.STONE);
        }
        boolean ok = t == Void.class || t == Particle.DustTransition.class || t == Particle.DustOptions.class
                || t == BlockData.class || t == ItemStack.class || t == Color.class || t == Float.class || t == Integer.class;
        if (!ok) {
            warn.accept("particle \"" + name + "\" needs " + t.getSimpleName() + " data, which effect files can't set");
            return null;
        }
        if (t == BlockData.class && blocks.isEmpty()) blocks = List.of("ground");
        return new ParticleData(p, t, color, to, size, blocks, item);
    }

    /** The data object for one spawn; "ground" reads the real block under {@code f}. */
    Object data(Frame f, Random r) {
        if (type == Void.class) return null;
        Color c = color != null ? color : Color.WHITE;
        if (type == Particle.DustTransition.class) return new Particle.DustTransition(c, toColor != null ? toColor : c, size);
        if (type == Particle.DustOptions.class) return new Particle.DustOptions(c, size);
        if (type == Color.class) return c;
        if (type == Float.class) return 0f;
        if (type == Integer.class) return 0;
        if (type == ItemStack.class) return item;
        return blockData(blocks, f, r);
    }

    /** A block from a list ("ground" = the real block under the frame, falling back to stone). */
    static BlockData blockData(List<String> blocks, Frame f, Random r) {
        String b = blocks.isEmpty() ? "ground" : blocks.get(r.nextInt(blocks.size()));
        if ("ground".equals(b)) {
            Block g = Frame.groundBlock(f.world, f.x, f.y + 0.05, f.z, 4);
            return g != null ? g.getBlockData() : Material.STONE.createBlockData();
        }
        Material m = Material.matchMaterial(b);
        return m != null && m.isBlock() ? m.createBlockData() : Material.STONE.createBlockData();
    }
}
