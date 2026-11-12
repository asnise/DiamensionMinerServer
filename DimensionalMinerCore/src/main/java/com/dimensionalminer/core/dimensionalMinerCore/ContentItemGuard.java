package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * Content items reuse a vanilla item as their base (payload "icon"), so they keep its vanilla
 * behaviour: an Engrave Solvent on a honey bottle could be drunk and lost. Eating or drinking a
 * content item is cancelled unless its category is FOOD; game systems consume them instead.
 */
public final class ContentItemGuard implements Listener {

    private final ContentRegistryManager registry;

    public ContentItemGuard(ContentRegistryManager registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!item.hasItemMeta()) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(registry.getDefIdKey(), PersistentDataType.STRING);
        if (id == null) return;
        ItemDefinition def = registry.getItem(id);
        // Unknown ids (content removed) are blocked too: better safe than losing the stack.
        if (def != null && def.getCategory().toUpperCase(Locale.ROOT).contains("FOOD")) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text(
                (def != null ? def.getDisplay() : "This item") + " can't be eaten or drunk; it is used by game systems.",
                NamedTextColor.GRAY));
    }
}
