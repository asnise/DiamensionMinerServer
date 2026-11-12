package com.dimensionalminer.core.dimensionalMinerCore.craft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for the custom-textured chest menus (style A). With the UI pack loaded the title is a font
 * glyph that draws the whole background and buttons are invisible items over the painted buttons;
 * without the pack the same slots show plain items, so the menu still works.
 * Every click in the top or bottom inventory is cancelled; subclasses get the slot.
 */
public abstract class GuiMenu implements InventoryHolder {

    public static final int ROWS = 6;
    protected static final Key GUI_FONT = Key.key("dm", "gui");
    /** -8 px then the background glyph: title text starts at x=8, the glyph must start at x=0. */
    private static final String SHIFT_LEFT_8 = "";

    protected final CraftService service;
    protected final Player player;
    protected final boolean pack;
    protected Inventory inventory;

    protected GuiMenu(CraftService service, Player player, boolean pack) {
        this.service = service;
        this.player = player;
        this.pack = pack;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected Component title(char glyph, String fallback) {
        if (!pack) return Component.text(fallback);
        return Component.text(SHIFT_LEFT_8 + glyph).font(GUI_FONT).color(NamedTextColor.WHITE);
    }

    /** Click in the menu (top) inventory. */
    protected abstract void clickTop(int slot, boolean shift);

    /** Click in the player's own inventory (slot index of the player inventory). */
    protected void clickBottom(int slot) {}

    protected void closed() {}

    // ------------------------------------------------------------------ item helpers

    protected static Component text(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** Sprite from the pack (dm:&lt;model&gt; on paper) or the fallback material without it. */
    protected ItemStack sprite(String model, Material fallback, Component name, List<Component> lore) {
        ItemStack s = new ItemStack(pack ? Material.PAPER : fallback);
        ItemMeta m = s.getItemMeta();
        if (pack) m.setItemModel(new NamespacedKey("dm", model));
        m.displayName(name);
        if (lore != null && !lore.isEmpty()) m.lore(lore);
        s.setItemMeta(m);
        return s;
    }

    /** A painted button: invisible when enabled, dimmed when disabled (pack); coloured pane otherwise. */
    protected ItemStack button(boolean enabled, Material fallbackOn, Component name, List<Component> lore) {
        return sprite(enabled ? "blank" : "dim", enabled ? fallbackOn : Material.GRAY_STAINED_GLASS_PANE, name, lore);
    }

    /** Fills empty slots with blank panes in fallback mode (pack mode leaves the background visible). */
    protected void fillEmpty() {
        if (pack) return;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = pane.getItemMeta();
        m.setHideTooltip(true);
        pane.setItemMeta(m);
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, pane);
    }

    protected static List<Component> lines(Component... c) {
        return new ArrayList<>(List.of(c));
    }

    protected static int slot(int row, int col) {
        return row * 9 + col;
    }

    // ------------------------------------------------------------------ listener

    public static final class Listen implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof GuiMenu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() == null) return;
            if (event.getClickedInventory().equals(event.getView().getTopInventory())) {
                menu.clickTop(event.getSlot(), event.isShiftClick());
            } else {
                menu.clickBottom(event.getSlot());
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof GuiMenu) event.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (event.getInventory().getHolder() instanceof GuiMenu menu) menu.closed();
        }

        /** Leaving with a menu open: closed() must still run (it is idempotent in every menu). */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof GuiMenu menu) menu.closed();
        }
    }
}
