package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GrantsMenu implements Listener, InventoryHolder {

    // Variables/Fields
    public static final int CHEST_SIZE = 54;
    public static final int CONTENT_SLOTS = 45; // Rows 0-4 (0 to 44)
    public static final int PREV_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private final DimensionalMinerCore plugin;
    private final Player player;
    private int currentPage = 1;
    private final Inventory inventory;
    private final List<GrantItemEntry> pendingItems = new ArrayList<>();
    private final Map<Integer, GrantItemEntry> slotToGrantMap = new HashMap<>();

    private static final Map<UUID, GrantsMenu> openMenus = new ConcurrentHashMap<>();

    // Properties
    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    // Constructors
    public GrantsMenu(DimensionalMinerCore plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, CHEST_SIZE, Component.text("Reward Grants & Mailbox", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
    }

    // Public Methods
    public static void open(DimensionalMinerCore plugin, Player player) {
        GrantsMenu menu = new GrantsMenu(plugin, player);
        openMenus.put(player.getUniqueId(), menu);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        menu.loadAndDisplay(1);
    }

    public void loadAndDisplay(int page) {
        this.currentPage = Math.max(1, page);
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<GrantItemEntry> entries = new ArrayList<>();
            String query = "SELECT grant_id, payload FROM reward_grants WHERE player_id = ? AND state = 'AVAILABLE' ORDER BY created_at ASC;";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, playerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID grantId = (UUID) rs.getObject("grant_id");
                        String payloadJson = rs.getString("payload");
                        List<ItemStack> items = parseItems(payloadJson);
                        for (ItemStack item : items) {
                            entries.add(new GrantItemEntry(grantId, item));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load grants for " + player.getName() + ": " + e.getMessage());
                player.sendMessage(Component.text("[Mailbox] Database error while loading grants.", NamedTextColor.RED));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    openMenus.remove(playerId);
                    return;
                }
                this.pendingItems.clear();
                this.pendingItems.addAll(entries);
                renderPage();
                player.openInventory(this.inventory);
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player clicker)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof GrantsMenu menu)) return;
        if (menu != this) return;

        int rawSlot = event.getRawSlot();

        // Prevent shift-clicking or dragging items from player inventory into the grants chest
        if (rawSlot >= CHEST_SIZE) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        // Always cancel direct placement into top inventory
        event.setCancelled(true);

        if (rawSlot < 0 || rawSlot >= CHEST_SIZE) return;

        // Navigation row handling
        int totalItems = pendingItems.size();
        int totalPages = (int) Math.ceil((double) totalItems / CONTENT_SLOTS);
        if (totalPages == 0) totalPages = 1;

        if (rawSlot == PREV_SLOT) {
            if (currentPage > 1) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                currentPage--;
                renderPage();
            }
            return;
        }

        if (rawSlot == NEXT_SLOT) {
            if (currentPage < totalPages) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                currentPage++;
                renderPage();
            }
            return;
        }

        if (rawSlot >= CONTENT_SLOTS) {
            // Other slots in navigation/control row are decorative
            return;
        }

        // Content slot clicked - Attempt to claim the item
        GrantItemEntry entry = slotToGrantMap.get(rawSlot);
        if (entry == null) return;

        if (clicker.getInventory().firstEmpty() == -1) {
            clicker.sendMessage(Component.text("[Mailbox] Inventory is full! Make space first.", NamedTextColor.RED));
            clicker.playSound(clicker.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        // Give item to player and remove from chest slot immediately
        slotToGrantMap.remove(rawSlot);
        pendingItems.remove(entry);
        inventory.setItem(rawSlot, null);

        clicker.getInventory().addItem(entry.item.clone());
        clicker.playSound(clicker.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.4f);

        String itemName = entry.item.getItemMeta() != null && entry.item.getItemMeta().hasDisplayName()
                ? entry.item.getItemMeta().getDisplayName()
                : entry.item.getType().name();
        clicker.sendMessage(Component.text("[Mailbox] Claimed: ", NamedTextColor.GREEN)
                .append(Component.text(entry.item.getAmount() + "x ", NamedTextColor.WHITE))
                .append(Component.text(itemName, NamedTextColor.AQUA)));

        // Update database asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            markGrantClaimed(entry.grantId);
        });

        // Re-render control bar in case total page count changed
        renderNavigationControls();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() == this) {
            for (int slot : event.getRawSlots()) {
                if (slot < CHEST_SIZE) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() == this) {
            openMenus.remove(player.getUniqueId());
            org.bukkit.event.HandlerList.unregisterAll(this);
        }
    }

    // Private Methods
    private void renderPage() {
        inventory.clear();
        slotToGrantMap.clear();

        int totalItems = pendingItems.size();
        int startIndex = (currentPage - 1) * CONTENT_SLOTS;
        int endIndex = Math.min(startIndex + CONTENT_SLOTS, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            GrantItemEntry entry = pendingItems.get(i);
            int slot = i - startIndex;
            slotToGrantMap.put(slot, entry);
            inventory.setItem(slot, entry.item.clone());
        }

        renderNavigationControls();
    }

    private void renderNavigationControls() {
        int totalItems = pendingItems.size();
        int totalPages = (int) Math.ceil((double) totalItems / CONTENT_SLOTS);
        if (totalPages == 0) totalPages = 1;

        ItemStack filler = createFillerPane();
        for (int slot = CONTENT_SLOTS; slot < CHEST_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        // Previous button
        if (currentPage > 1) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            meta.displayName(Component.text("<- Previous Page (" + (currentPage - 1) + ")", NamedTextColor.YELLOW, TextDecoration.BOLD));
            prev.setItemMeta(meta);
            inventory.setItem(PREV_SLOT, prev);
        } else {
            inventory.setItem(PREV_SLOT, filler);
        }

        // Info item
        ItemStack info = new ItemStack(Material.CHEST);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("Page " + currentPage + " / " + totalPages, NamedTextColor.GOLD, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Pending Items: " + totalItems, NamedTextColor.GRAY));
        lore.add(Component.text("Click any item above to claim it!", NamedTextColor.GREEN));
        lore.add(Component.text("Claimed items cannot be put back.", NamedTextColor.RED));
        infoMeta.lore(lore);
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        // Next button
        if (currentPage < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            meta.displayName(Component.text("Next Page (" + (currentPage + 1) + ") ->", NamedTextColor.YELLOW, TextDecoration.BOLD));
            next.setItemMeta(meta);
            inventory.setItem(NEXT_SLOT, next);
        } else {
            inventory.setItem(NEXT_SLOT, filler);
        }
    }

    private ItemStack createFillerPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        return pane;
    }

    private List<ItemStack> parseItems(String json) {
        List<ItemStack> list = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("items") && obj.get("items").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("items");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject itemObj = el.getAsJsonObject();
                    String itemId = itemObj.has("item_id") ? itemObj.get("item_id").getAsString()
                            : itemObj.has("definition_id") ? itemObj.get("definition_id").getAsString()
                            : itemObj.has("id") ? itemObj.get("id").getAsString()
                            : itemObj.has("material") ? itemObj.get("material").getAsString()
                            : null;
                    int amount = itemObj.has("amount") ? itemObj.get("amount").getAsInt() : 1;
                    if (itemId != null) {
                        if (plugin.getContentRegistryManager() != null && plugin.getContentRegistryManager().hasItem(itemId)) {
                            list.add(plugin.getContentRegistryManager().createItemStack(itemId, amount));
                        } else {
                            Material mat = Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
                            if (mat != null) {
                                list.add(new ItemStack(mat, Math.max(1, amount)));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void markGrantClaimed(UUID grantId) {
        String update = "UPDATE reward_grants SET state = 'CLAIMED', claimed_at = now() WHERE grant_id = ?;";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(update)) {
            stmt.setObject(1, grantId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to mark grant as claimed: " + e.getMessage());
        }
    }

    public record GrantItemEntry(UUID grantId, ItemStack item) {}
}
