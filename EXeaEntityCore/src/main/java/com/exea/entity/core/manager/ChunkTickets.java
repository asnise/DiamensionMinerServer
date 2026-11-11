package com.exea.entity.core.manager;

import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reference-counted plugin chunk tickets. Bukkit keeps a single ticket per (plugin, chunk), not a
 * count, so two entities in one chunk must share it: the ticket is added by the first holder and
 * removed only when the last holder lets go. Main thread only.
 */
final class ChunkTickets {

    private record Key(UUID world, int x, int z) {}

    private static final Map<Key, Integer> holders = new HashMap<>();

    private ChunkTickets() {}

    static void acquire(Plugin plugin, Chunk chunk) {
        Key key = new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        int count = holders.merge(key, 1, Integer::sum);
        if (count == 1) {
            chunk.addPluginChunkTicket(plugin);
        }
    }

    static void release(Plugin plugin, Chunk chunk) {
        Key key = new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Integer count = holders.get(key);
        if (count == null) return;
        if (count <= 1) {
            holders.remove(key);
            chunk.removePluginChunkTicket(plugin);
        } else {
            holders.put(key, count - 1);
        }
    }
}
